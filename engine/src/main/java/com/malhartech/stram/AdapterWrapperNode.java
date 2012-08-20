/**
 * Copyright (c) 2012-2012 Malhar, Inc. All rights reserved.
 */
package com.malhartech.stram;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.malhartech.dag.AbstractNode;
import com.malhartech.dag.InputAdapter;
import com.malhartech.dag.NodeConfiguration;
import com.malhartech.dag.Sink;
import com.malhartech.dag.Stream;
import com.malhartech.dag.StreamConfiguration;
import com.malhartech.dag.StreamContext;
import com.malhartech.dag.Tuple;
import com.malhartech.stram.conf.TopologyBuilder;

/**
 * Wrapper node to connects adapter "stream" to another source or sink stream. Provides uniform view of adapter as node for stram deployment and monitoring.
 */
public class AdapterWrapperNode extends AbstractNode implements Sink
{
  private final static Logger LOG = LoggerFactory.getLogger(AdapterWrapperNode.class);
  public static final String KEY_STREAM_CLASS_NAME = "streamClassName";
  public static final String KEY_IS_INPUT = "input";
  /**
   * Window id for underlying stream initialization.
   * Passed through properties, since the adapter nodes are not part of the backup protocol. 
   **/
  public static final String CHECKPOINT_WINDOW_ID = "checkPointWindowId";
  
  private boolean isInput;
  private String streamClassName;
  private long checkPointWindowId;
  private Stream adapterStream = null;

  public String getStreamClassName()
  {
    return streamClassName;
  }

  public void setStreamClassName(String streamClassName)
  {
    this.streamClassName = streamClassName;
  }

  public long getCheckPointWindowId() {
    return checkPointWindowId;
  }

  public void setCheckPointWindowId(long checkPointWindowId) {
    this.checkPointWindowId = checkPointWindowId;
  }

  public boolean isInput()
  {
    return isInput;
  }

  public void setInput(boolean value)
  {
    isInput = value;
  }

  public Stream getAdapterStream()
  {
    return adapterStream;
  }

  @Override
  public void process(Object payload)
  {

    throw new UnsupportedOperationException("Adapter nodes do not implement process. " + payload);
  }

  @Override
  public void doSomething(Tuple t)
  {
    LOG.debug("sending tuple {}", t);
    this.sink.sink(t);
  }

  @Override
  public void setup(NodeConfiguration config)
  {
    Map<String, String> props = config.getDagProperties();
    props.put(TopologyBuilder.STREAM_CLASSNAME, this.streamClassName);
    StreamConfiguration streamConf = new StreamConfiguration(props);
    if (isInput()) {
      adapterStream = initAdapterStream(streamConf, this);
    }
    else {
      adapterStream = initAdapterStream(streamConf, null);
    }
    LOG.debug("adapter stream {} with startWindowId {}", adapterStream, adapterStream.getContext().getStartingWindowId());
  }

  @Override
  public void teardown()
  {
    if (adapterStream != null) {
      adapterStream.teardown();
      adapterStream.setContext(Stream.DISCONNECTED_STREAM_CONTEXT);
    }
  }
  private com.malhartech.dag.StreamContext sink;

  @Override
  public void addOutputStream(StreamContext context)
  {
    this.sink = context;
  }

  @Override
  public Sink getSink(StreamContext context)
  {
    if (isInput()) {
      return this;
    }

    return (Sink) adapterStream;
  }

  public static <T extends Stream> T initAdapterStream(StreamConfiguration streamConf, AbstractNode targetNode)
  {
    Map<String, String> properties = streamConf.getDagProperties();
    String className = properties.get(TopologyBuilder.STREAM_CLASSNAME);
    if (className == null) {
      // should have been caught during submit validation
      throw new IllegalArgumentException(String.format("Stream class not configured (key '%s')", TopologyBuilder.STREAM_CLASSNAME));
    }
    try {
      Class<?> clazz = Class.forName(className);
      Class<? extends Stream> subClass = clazz.asSubclass(Stream.class);
      Constructor<? extends Stream> c = subClass.getConstructor();
      @SuppressWarnings("unchecked")
      T instance = (T) c.newInstance();
      // populate custom properties
      BeanUtils.populate(instance, properties);

      instance.setup(streamConf);

      long checkpointWindowId = streamConf.getLong(CHECKPOINT_WINDOW_ID, 0);
      StreamContext ctx = new StreamContext(streamConf.get(TopologyBuilder.STREAM_SOURCENODE),
          streamConf.get(TopologyBuilder.STREAM_TARGETNODE));
      ctx.setStartingWindowId(checkpointWindowId);
      if (targetNode == null) {
        /*
         * This is output adapter so it needs to implement the Sink interface.
         */
        if (instance instanceof Sink) {
//          logger.info(ctx + " setting selfsink for instance " + instance);
          ctx.setSink((Sink) instance);
        }
      }
      else {
        Sink sink = targetNode.getSink(ctx);
//        logger.info(ctx + " setting sink for instance " + instance + " to " + sink);
        ctx.setSink(sink);
      }

      ctx.setSerde(StramUtils.getSerdeInstance(properties));
      instance.setContext(ctx);

      return instance;
    }
    catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Node class not found: " + className, e);
    }
    catch (IllegalAccessException e) {
      throw new IllegalArgumentException("Error setting node properties", e);
    }
    catch (InvocationTargetException e) {
      throw new IllegalArgumentException("Error setting node properties", e);
    }
    catch (SecurityException e) {
      throw new IllegalArgumentException("Error creating instance of class: " + className, e);
    }
    catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("Constructor with NodeContext not found: " + className, e);
    }
    catch (InstantiationException e) {
      throw new IllegalArgumentException("Failed to instantiate: " + className, e);
    }
  }

  @Override
  public void handleIdleTimeout()
  {
    if (isInput() && ((InputAdapter) adapterStream).hasFinished()) {
      stop();
    }
  }
}
