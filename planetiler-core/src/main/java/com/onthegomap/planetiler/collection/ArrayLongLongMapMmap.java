package com.onthegomap.planetiler.collection;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.onthegomap.planetiler.util.FileUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public class ArrayLongLongMapMmap implements LongLongMap.ParallelWrites {

  FileChannel writeChannel;
  static final int segmentBits = 27; // 128MB
  static final int maxPendingSegments = 20; // 1GB
  static final long segmentMask = (1L << segmentBits) - 1;
  static final long segmentBytes = 1 << segmentBits;
  Semaphore limitInMemoryChunks = new Semaphore(maxPendingSegments);
  final Path path;
  MappedByteBuffer[] segmentsArray;
  CopyOnWriteArrayList<AtomicLong> segments = new CopyOnWriteArrayList<>();
  final ConcurrentMap<Long, ByteBuffer> writeBuffers = new ConcurrentHashMap<>();
  private FileChannel readChannel = null;

  public ArrayLongLongMapMmap(Path path) {
    this.path = path;
    try {
      writeChannel = FileChannel.open(path, WRITE, CREATE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void init() {
    try {
      for (Long oldKey : writeBuffers.keySet()) {
        // no one else needs this segment, flush it
        var toFlush = writeBuffers.remove(oldKey);
        if (toFlush != null) {
          try {
            writeChannel.write(toFlush, oldKey << segmentBits);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }
      writeChannel.close();
      readChannel = FileChannel.open(path, READ, WRITE);
      long outIdx = readChannel.size() + 8;
      int segmentCount = (int) (outIdx / segmentBytes + 1);
      segmentsArray = new MappedByteBuffer[segmentCount];
      int i = 0;
      for (long segmentStart = 0; segmentStart < outIdx; segmentStart += segmentBytes) {
        long segmentLength = Math.min(segmentBytes, outIdx - segmentStart);
        MappedByteBuffer buffer = readChannel.map(FileChannel.MapMode.READ_ONLY, segmentStart, segmentLength);
        // TODO madvise
        segmentsArray[i++] = buffer;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  record ToFlush(ByteBuffer buf, long offset) {}

  @Override
  public Writer newWriter() {
    AtomicLong currentSeg = new AtomicLong(-1);
    segments.add(currentSeg);
    return new Writer() {
      long lastSegment = -1;
      long segmentOffset = -1;
      ByteBuffer buffer = null;

      @Override
      public void put(long key, long value) {
        long offset = key << 3;
        long segment = offset >>> segmentBits;
        if (segment > lastSegment) {
          List<ToFlush> chunksToFlush = new ArrayList<>();
          synchronized (writeBuffers) {
            currentSeg.set(segment);
            var minSegment = segments.stream().mapToLong(AtomicLong::get).min().getAsLong();
            for (Long oldKey : writeBuffers.keySet()) {
              if (oldKey < minSegment) {
                // no one else needs this segment, flush it
                var toFlush = writeBuffers.remove(oldKey);
                if (toFlush != null) {
                  chunksToFlush.add(new ToFlush(toFlush, oldKey << segmentBits));
//                  limitInMemoryChunks.release();
                }
              }
            }
            buffer = writeBuffers.computeIfAbsent(segment, i -> {
//              try {
//                limitInMemoryChunks.acquire();
//              } catch (InterruptedException e) {
//                e.printStackTrace();
//                throw new RuntimeException(e);
//              }
              return ByteBuffer.allocateDirect(1 << segmentBits);
            });
          }
          lastSegment = segment;
          segmentOffset = segment << segmentBits;
          for (var chunk : chunksToFlush) {
            try {
              writeChannel.write(chunk.buf, chunk.offset);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            chunk.buf.clear();
          }
        }
        buffer.putLong((int) (offset - segmentOffset), value);
      }
    };
  }

  private volatile boolean inited = false;

  private void initOnce() {
    if (!inited) {
      synchronized (this) {
        if (!inited) {
          init();
          inited = true;
        }
      }
    }
  }

  @Override
  public long get(long key) {
    initOnce();
    long byteOffset = key << 3;
    int idx = (int) (byteOffset >>> segmentBits);
    int offset = (int) (byteOffset & segmentMask);
    long result = segmentsArray[idx].getLong(offset);
    return result == 0 ? LongLongMap.MISSING_VALUE : result;
  }

  @Override
  public long diskUsageBytes() {
    return FileUtils.size(path);
  }

  @Override
  public long estimateMemoryUsageBytes() {
    return 0;
  }

  @Override
  public void close() throws IOException {
    if (readChannel != null) {
      readChannel.close();
      readChannel = null;
      FileUtils.delete(path);
    }
  }
}