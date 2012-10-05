/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.util;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.util.Collection;

/**
 * Provides a non-premium implementation of circular buffer<p>
 * <br>
 *
 */
public class SynchronizedCircularBuffer<T> implements CBuffer<T>
{
  private static final BufferUnderflowException underflow = new BufferUnderflowException();
  private static final BufferOverflowException overflow = new BufferOverflowException();
  private final T[] buffer;
  private final int buffermask;
  private int tail;
  private int head;

  /**
   *
   * Constructing a circular buffer of 'n' integers<p>
   * <br>
   *
   * @param n size of the buffer to be constructed
   * <br>
   */
  @SuppressWarnings("unchecked")
  public SynchronizedCircularBuffer(int n)
  {
    int i = 1;
    while (i < n) {
      i = i << 1;
    }

    buffer = (T[])new Object[i];
    buffermask = i - 1;
  }

  /**
   *
   * Add object at the head<p>
   * <br>
   *
   * @param toAdd object to be added
   *
   */
  @Override
  public synchronized void add(T toAdd)
  {
    if (head - tail <= buffermask) {
      buffer[head++ & buffermask] = toAdd;
      return;
    }

    throw overflow;
  }

  /**
   *
   * Get object from the tail<p>
   * <br>
   *
   * @return object removed from the buffer returned
   * <br>
   */
  @Override
  public synchronized T get()
  {
    if (head > tail) {
      return buffer[tail++ & buffermask];
    }

    throw underflow;
  }

  public synchronized T peek()
  {
    if (head > tail) {
      return buffer[tail & buffermask];
    }

    return null;
  }

  /**
   *
   * Number of objects in the buffer<p>
   * <br>
   *
   * @return Number of objects in the buffer
   * <br>
   */
  @Override
  public final synchronized int size()
  {
    return head - tail;
  }

  /**
   *
   * Total design capacity of the buffer<p>
   * <br>
   *
   * @return Total return capacity of the buffer
   * <br>
   */
  public int capacity()
  {
    return buffermask + 1;
  }

  /**
   *
   * Drain the buffer<p>
   * <br>
   *
   * @param container {@link java.util.Collection} class to which the buffer objects are added
   * @return Number of objects removed from the buffer
   * <br>
   */
  public synchronized int drainTo(Collection<? super T> container)
  {
    int size = size();

    while (head > tail) {
      container.add(buffer[tail++ & buffermask]);
    }

    return size;
  }

  /**
   *
   * Printing status for debugging<p>
   * <br>
   *
   * @return String containing capacity, head, and tail
   * <br>
   */
  @Override
  public synchronized String toString()
  {
    return "CircularBuffer(capacity=" + (buffermask + 1) + ", head=" + head + ", tail=" + tail + ")";
  }
}