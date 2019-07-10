package org.apache.lucene.codecs.compressing;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Random;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.lucene41.Lucene41Codec;

import com.carrotsearch.randomizedtesting.generators.RandomInts;

/**
 * A codec that uses {@link CompressingStoredFieldsFormat} for its stored
 * fields and delegates to {@link Lucene41Codec} for everything else.
 */
public abstract class CompressingCodec extends FilterCodec {

  /**
   * Create a random instance.
   */
  public static CompressingCodec randomInstance(Random random, int chunkSize) {
    switch (random.nextInt(4)) {
    case 0:
      return new FastCompressingCodec(chunkSize);
    case 1:
      return new FastDecompressionCompressingCodec(chunkSize);
    case 2:
      return new HighCompressionCompressingCodec(chunkSize);
    case 3:
      return new DummyCompressingCodec(chunkSize);
    default:
      throw new AssertionError();
    }
  }

  public static CompressingCodec randomInstance(Random random) {
    return randomInstance(random, RandomInts.randomIntBetween(random, 1, 500));
  }

  private final CompressingStoredFieldsFormat storedFieldsFormat;

  public CompressingCodec(String name, CompressionMode compressionMode, int chunkSize) {
    super(name, new Lucene41Codec());
    this.storedFieldsFormat = new CompressingStoredFieldsFormat(name, compressionMode, chunkSize);
  }

  @Override
  public StoredFieldsFormat storedFieldsFormat() {
    return storedFieldsFormat;
  }

  @Override
  public String toString() {
    return getName() + "(storedFieldsFormat=" + storedFieldsFormat + ")";
  }
}
