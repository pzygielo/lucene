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

package org.apache.lucene.codecs;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.Bits;

/** Reads vectors from an index. */
public abstract class KnnVectorsReader implements Closeable {

  /** Sole constructor */
  protected KnnVectorsReader() {}

  /**
   * Checks consistency of this reader.
   *
   * <p>Note that this may be costly in terms of I/O, e.g. may involve computing a checksum value
   * against large data files.
   *
   * @lucene.internal
   */
  public abstract void checkIntegrity() throws IOException;

  /**
   * Returns the {@link FloatVectorValues} for the given {@code field}. The behavior is undefined if
   * the given field doesn't have KNN vectors enabled on its {@link FieldInfo}. The return value is
   * never {@code null}.
   */
  public abstract FloatVectorValues getFloatVectorValues(String field) throws IOException;

  /**
   * Returns the {@link ByteVectorValues} for the given {@code field}. The behavior is undefined if
   * the given field doesn't have KNN vectors enabled on its {@link FieldInfo}. The return value is
   * never {@code null}.
   */
  public abstract ByteVectorValues getByteVectorValues(String field) throws IOException;

  /**
   * Return the k nearest neighbor documents as determined by comparison of their vector values for
   * this field, to the given vector, by the field's similarity function. The score of each document
   * is derived from the vector similarity in a way that ensures scores are positive and that a
   * larger score corresponds to a higher ranking.
   *
   * <p>The search is allowed to be approximate, meaning the results are not guaranteed to be the
   * true k closest neighbors. For large values of k (for example when k is close to the total
   * number of documents), the search may also retrieve fewer than k documents.
   *
   * <p>The returned {@link TopDocs} will contain a {@link ScoreDoc} for each nearest neighbor, in
   * order of their similarity to the query vector (decreasing scores). The {@link TotalHits}
   * contains the number of documents visited during the search. If the search stopped early because
   * it hit {@code visitedLimit}, it is indicated through the relation {@code
   * TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO}.
   *
   * <p>The behavior is undefined if the given field doesn't have KNN vectors enabled on its {@link
   * FieldInfo}.
   *
   * @param field the vector field to search
   * @param target the vector-valued query
   * @param knnCollector a KnnResults collector and relevant settings for gathering vector results
   * @param acceptDocs {@link Bits} that represents the allowed documents to match, or {@code null}
   *     if they are all allowed to match.
   */
  public abstract void search(
      String field, float[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException;

  /**
   * Return the k nearest neighbor documents as determined by comparison of their vector values for
   * this field, to the given vector, by the field's similarity function. The score of each document
   * is derived from the vector similarity in a way that ensures scores are positive and that a
   * larger score corresponds to a higher ranking.
   *
   * <p>The search is allowed to be approximate, meaning the results are not guaranteed to be the
   * true k closest neighbors. For large values of k (for example when k is close to the total
   * number of documents), the search may also retrieve fewer than k documents.
   *
   * <p>The returned {@link TopDocs} will contain a {@link ScoreDoc} for each nearest neighbor, in
   * order of their similarity to the query vector (decreasing scores). The {@link TotalHits}
   * contains the number of documents visited during the search. If the search stopped early because
   * it hit {@code visitedLimit}, it is indicated through the relation {@code
   * TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO}.
   *
   * <p>The behavior is undefined if the given field doesn't have KNN vectors enabled on its {@link
   * FieldInfo}.
   *
   * @param field the vector field to search
   * @param target the vector-valued query
   * @param knnCollector a KnnResults collector and relevant settings for gathering vector results
   * @param acceptDocs {@link Bits} that represents the allowed documents to match, or {@code null}
   *     if they are all allowed to match.
   */
  public abstract void search(
      String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException;

  /**
   * Returns an instance optimized for merging. This instance may only be consumed in the thread
   * that called {@link #getMergeInstance()}.
   *
   * <p>The default implementation returns {@code this}
   */
  public KnnVectorsReader getMergeInstance() {
    return this;
  }

  /**
   * Optional: reset or close merge resources used in the reader
   *
   * <p>The default implementation is empty
   */
  public void finishMerge() throws IOException {}

  /**
   * Returns the desired size of off-heap memory for the given field. This size can be used to help
   * determine the memory requirements for optimal search performance, which can be greatly affected
   * by page faults when not enough memory is available.
   *
   * <p>For reporting purposes, the size of the off-heap index structures is broken down by their
   * file extension, which provides a logical categorization of their purpose, e.g. the {@code
   * Lucene99HnswVectorsFormat} stores the HNSW graph neighbours lists in a file with the "vex"
   * extension.
   *
   * <p>The long value is the size in bytes of the off-heap space needed if the associated index
   * structure were to be fully loaded in memory. While somewhat analogous to {@link
   * Accountable#ramBytesUsed()} (which reports actual on-heap memory usage), the sizes reported by
   * this method are not actual usage but rather the amount of available memory needed to fully load
   * the index into memory, rather than an actual RAM usage requirement.
   *
   * <p>To determine the total desired off-heap memory size for the given field:
   *
   * <pre>{@code
   * getOffHeapByteSize(field).values().stream().mapToLong(Long::longValue).sum();
   * }</pre>
   *
   * <p>The default implementation returns an empty map.
   *
   * @param fieldInfo the fieldInfo
   * @return a map of the desired off-heap memory requirements by category
   * @lucene.experimental
   */
  public Map<String, Long> getOffHeapByteSize(FieldInfo fieldInfo) {
    return Map.of();
  }

  /**
   * Merges the maps returned by {@link #getOffHeapByteSize(FieldInfo)}.
   *
   * <p>This method is a convenience for aggregating the desired off-heap memory requirements for
   * several fields. The keys in the returned map are a union of the keys in the given maps. Entries
   * with the same key are summed.
   *
   * @lucene.experimental
   */
  public static Map<String, Long> mergeOffHeapByteSizeMaps(
      Map<String, Long> map1, Map<String, Long> map2) {
    return Stream.of(map1, map2)
        .flatMap(map -> map.entrySet().stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
  }
}
