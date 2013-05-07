/*
 *  Copyright (c) 2012-2013 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.stram;

import com.malhartech.api.Context.OperatorContext;
import com.malhartech.api.Operator;
import com.malhartech.api.Sink;
import com.malhartech.api.StreamCodec;
import com.malhartech.bufferserver.packet.MessageType;
import com.malhartech.tuple.Tuple;
import com.malhartech.util.PubSubWebSocketClient;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import com.malhartech.util.Fragment;
import com.malhartech.util.HdfsPartFileCollection;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.eclipse.jetty.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author David Yan <davidyan@malhar-inc.com>
 */
public class TupleRecorder implements Operator
{
  public static final String VERSION = "1.1";
  private int totalTupleCount = 0;
  private HashMap<String, PortInfo> portMap = new HashMap<String, PortInfo>(); // used for output portInfo <name, id> map
  private HashMap<String, PortCount> portCountMap = new HashMap<String, PortCount>(); // used for tupleCount of each port <name, count> map
  private transient long currentWindowId = -1;
  private transient ArrayList<Range> windowIdRanges = new ArrayList<Range>();
  //private transient long partBeginWindowId = -1;
  private String recordingName = "Untitled";
  private final long startTime = System.currentTimeMillis();
  private int nextPortIndex = 0;
  private HashMap<String, Sink<Object>> sinks = new HashMap<String, Sink<Object>>();
  private transient long endWindowTuplesProcessed = 0;
  @SuppressWarnings("rawtypes")
  private Class<? extends StreamCodec> streamCodecClass = JsonStreamCodec.class;
  private transient StreamCodec<Object> streamCodec;
  private static final Logger logger = LoggerFactory.getLogger(TupleRecorder.class);
  private URI pubSubUrl = null;
  private int numSubscribers = 0;
  private PubSubWebSocketClient wsClient;
  private String recordingNameTopic;
  private HdfsPartFileCollection storage = new HdfsPartFileCollection()
  {
    @Override
    protected String getAndResetIndexExtraInfo()
    {
      String str;
      windowIdRanges.get(windowIdRanges.size() - 1).high = TupleRecorder.this.currentWindowId;
      str = convertToString(windowIdRanges);
      int i = 0;
      str += ":";
      String countStr;
      countStr = "{";
      for (String key: portCountMap.keySet()) {
        PortCount pc = portCountMap.get(key);
        if (i != 0) {
          countStr += ",";
        }
        countStr += "\"" + pc.id + "\"" + ":\"" + pc.count + "\"";
        i++;
        pc.count = 0;
        portCountMap.put(key, pc);
      }
      countStr += "}";
      str += countStr.length();
      str += ":" + countStr;
      windowIdRanges.clear();
      return str;
    }

  };

  public HdfsPartFileCollection getStorage()
  {
    return storage;
  }

  public RecorderSink newSink(String key)
  {
    RecorderSink recorderSink = new RecorderSink(key);
    sinks.put(key, recorderSink);
    return recorderSink;
  }

  /**
   * Sets the stream codec for serialization to write to the files
   * The serialization method must not produce newlines.
   * For serializations that produces binary, base64 is recommended.
   *
   * @param streamCodecClass
   */
  public void setStreamCodec(Class<? extends StreamCodec<Object>> streamCodecClass)
  {
    this.streamCodecClass = streamCodecClass;
  }

  public void setPubSubUrl(String pubSubUrl) throws URISyntaxException
  {
    this.pubSubUrl = new URI(pubSubUrl);
  }

  public Map<String, PortInfo> getPortInfoMap()
  {
    return Collections.unmodifiableMap(portMap);
  }

  public int getTotalTupleCount()
  {
    return totalTupleCount;
  }

  public Map<String, Sink<Object>> getSinkMap()
  {
    return Collections.unmodifiableMap(sinks);
  }

  /* defined for json information */
  public static class PortInfo
  {
    public String name;
    public String streamName;
    public String type;
    public int id;
  }

  /* defined for written tuple count of each port recorded in index file */
  public static class PortCount
  {
    public int id;
    public long count;
  }

  public static class RecordInfo
  {
    public long startTime;
    public String recordingName;
  }

  public static class Range
  {
    public long low = -1;
    public long high = -1;

    public Range()
    {
    }

    public Range(long low, long high)
    {
      this.low = low;
      this.high = high;
    }

    @Override
    public String toString()
    {
      return "[" + String.valueOf(low) + "," + String.valueOf(high) + "]";
    }

  }

  public String getRecordingName()
  {
    return recordingName;
  }

  public void setRecordingName(String recordingName)
  {
    this.recordingName = recordingName;
  }

  public long getStartTime()
  {
    return startTime;
  }

  public void addInputPortInfo(String portName, String streamName)
  {
    PortInfo portInfo = new PortInfo();
    portInfo.name = portName;
    portInfo.streamName = streamName;
    portInfo.type = "input";
    portInfo.id = nextPortIndex++;
    portMap.put(portName, portInfo);
    PortCount pc = new PortCount();
    pc.id = portInfo.id;
    pc.count = 0;
    portCountMap.put(portName, pc);
  }

  public void addOutputPortInfo(String portName, String streamName)
  {
    PortInfo portInfo = new PortInfo();
    portInfo.name = portName;
    portInfo.streamName = streamName;
    portInfo.type = "output";
    portInfo.id = nextPortIndex++;
    portMap.put(portName, portInfo);
    PortCount pc = new PortCount();
    pc.id = portInfo.id;
    pc.count = 0;
    portCountMap.put(portName, pc);
  }

  @Override
  public void teardown()
  {
    logger.info("Closing down tuple recorder.");
    this.storage.teardown();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void setup(OperatorContext context)
  {
    try {
      streamCodec = streamCodecClass.newInstance();
      storage.setup();

      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      bos.write((VERSION + "\n").getBytes());

      RecordInfo recordInfo = new RecordInfo();
      recordInfo.startTime = startTime;
      recordInfo.recordingName = recordingName;
      Fragment f = streamCodec.toByteArray(recordInfo).data;
      bos.write(f.buffer, f.offset, f.length);
      bos.write("\n".getBytes());

      for (PortInfo pi: portMap.values()) {
        f = streamCodec.toByteArray(pi).data;
        bos.write(f.buffer, f.offset, f.length);
        bos.write("\n".getBytes());
      }

      storage.writeMetaFile(bos.toByteArray());

      if (pubSubUrl != null) {
        recordingNameTopic = "tupleRecorder." + recordingName;
        try {
          setupWsClient();
        }
        catch (Exception ex) {
          logger.error("Cannot connect to daemon at {}", pubSubUrl);
        }
      }
    }
    catch (Exception ex) {
      logger.error("Trouble setting up tuple recorder", ex);
    }
  }

  private void setupWsClient() throws ExecutionException, IOException, InterruptedException, TimeoutException
  {
    wsClient = new PubSubWebSocketClient()
    {
      @Override
      public void onOpen(WebSocket.Connection connection)
      {
      }

      @Override
      public void onMessage(String type, String topic, Object data)
      {
        if (topic.equals(recordingNameTopic + ".numSubscribers")) {
          numSubscribers = Integer.valueOf((String)data);
          logger.info("Number of subscribers for {} is now {}", recordingName, numSubscribers);
        }
      }

      @Override
      public void onClose(int code, String message)
      {
        numSubscribers = 0;
      }

    };
    wsClient.setUri(pubSubUrl);
    wsClient.openConnection(500);
    wsClient.subscribeNumSubscribers(recordingNameTopic);
  }

  @Override
  public void beginWindow(long windowId)
  {
    if (this.currentWindowId != windowId) {
      if (windowId != this.currentWindowId + 1) {
        if (!windowIdRanges.isEmpty()) {
          windowIdRanges.get(windowIdRanges.size() - 1).high = this.currentWindowId;
        }
        Range range = new Range();
        range.low = windowId;
        windowIdRanges.add(range);
      }
      if (windowIdRanges.isEmpty()) {
        Range range = new Range();
        range.low = windowId;
        windowIdRanges.add(range);
      }
      this.currentWindowId = windowId;
      endWindowTuplesProcessed = 0;
      try {
        storage.writeDataItem(("B:" + windowId + "\n").getBytes(), false);
      }
      catch (IOException ex) {
        logger.error(ex.toString());
      }
    }
  }

  @Override
  public void endWindow()
  {
    if (++endWindowTuplesProcessed == portMap.size()) {
      try {
        storage.writeDataItem(("E:" + currentWindowId + "\n").getBytes(), false);
        logger.debug("Got last end window tuple.  Flushing...");
        storage.checkTurnover();
        if (pubSubUrl != null) {
          // check web socket connection
          if (!wsClient.isConnectionOpen()) {
            try {
              setupWsClient();
            }
            catch (Exception ex) {
              logger.error("Cannot connect to daemon");
            }
          }
        }
      }
      catch (IOException ex) {
        logger.error("Exception caught in endWindow", ex);
      }
    }
  }

  public void writeTuple(Object obj, String port)
  {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      Fragment f = streamCodec.toByteArray(obj).data;
      PortInfo pi = portMap.get(port);
      String str = "T:" + pi.id + ":" + f.length + ":";
      bos.write(str.getBytes());
      bos.write(f.buffer, f.offset, f.length);
      bos.write("\n".getBytes());
      PortCount pc = portCountMap.get(port);
      pc.count++;
      portCountMap.put(port, pc);

      storage.writeDataItem(bos.toByteArray(), true);
      //logger.debug("Writing tuple for port id {}", pi.id);
      //fsOutput.hflush();
      if (numSubscribers > 0) {
        publishTupleData(pi.id, obj);
      }
      ++totalTupleCount;
    }
    catch (IOException ex) {
      logger.error(ex.toString());
    }
  }

  public void writeControlTuple(Tuple tuple, String port)
  {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      PortInfo pi = portMap.get(port);
      Fragment f = streamCodec.toByteArray(tuple).data;
      String str = "C:" + pi.id + ":" + f.length + ":";
      bos.write(str.getBytes());
      bos.write(f.buffer, f.offset, f.length);
      bos.write("\n".getBytes());
      storage.writeDataItem(bos.toByteArray(), false);
    }
    catch (IOException ex) {
      logger.error(ex.toString());
    }
  }

  private static String convertToString(List<Range> ranges)
  {
    String result = "";
    int i = 0;
    for (Range range: ranges) {
      if (i++ > 0) {
        result += ",";
      }
      result += String.valueOf(range.low);
      result += "-";
      result += String.valueOf(range.high);
    }
    return result;
  }

  private void publishTupleData(int portId, Object obj)
  {
    try {
      if (pubSubUrl != null && wsClient.isConnectionOpen()) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put("portId", String.valueOf(portId));
        map.put("windowId", currentWindowId);
        map.put("data", obj);
        wsClient.publish(recordingNameTopic, map);
      }
    }
    catch (Exception ex) {
      logger.warn("Error posting to URL {}", pubSubUrl, ex);
    }
  }

  public class RecorderSink implements Sink<Object>
  {
    private final String portName;

    public RecorderSink(String portName)
    {
      this.portName = portName;
    }

    @Override
    public void put(Object payload)
    {
      // *** if it's not a control tuple, then (payload instanceof Tuple) returns false
      // In other words, if it's a regular tuple emitted by operators (payload), payload
      // is not an instance of Tuple (confusing... I know)
      if (payload instanceof Tuple) {
        Tuple tuple = (Tuple)payload;
        MessageType messageType = tuple.getType();
        if (messageType == MessageType.BEGIN_WINDOW) {
          beginWindow(tuple.getWindowId());
        }
        writeControlTuple(tuple, portName);
        if (messageType == MessageType.END_WINDOW) {
          endWindow();
        }
      }
      else {
        writeTuple(payload, portName);
      }
    }

    @Override
    public int getCount(boolean reset)
    {
      throw new UnsupportedOperationException("Not supported yet.");
    }

  }

}
