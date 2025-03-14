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
package org.apache.lucene.benchmark.byTask.tasks;

import java.util.concurrent.TimeUnit;
import org.apache.lucene.benchmark.byTask.PerfRunData;

/** Test Search task which counts number of searches. */
@SuppressWarnings("NonFinalStaticField")
public class CountingSearchTestTask extends SearchTask {

  public static int numSearches = 0;
  public static long startNanos;
  public static long lastNanos;
  public static long prevLastNanos;

  public CountingSearchTestTask(PerfRunData runData) {
    super(runData);
  }

  @Override
  public int doLogic() throws Exception {
    int res = super.doLogic();
    incrNumSearches();
    return res;
  }

  private static synchronized void incrNumSearches() {
    prevLastNanos = lastNanos;
    lastNanos = System.nanoTime();
    if (0 == numSearches) {
      startNanos = prevLastNanos = lastNanos;
    }
    numSearches++;
  }

  public long getElapsedMillis() {
    return TimeUnit.NANOSECONDS.toMillis(lastNanos - startNanos);
  }
}
