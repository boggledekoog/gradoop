/*
 * Copyright © 2014 - 2019 Leipzig University (Database Research Group)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradoop.flink.model.impl.operators.cypher.capf.result.functions;

import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.java.functions.FunctionAnnotation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.Collector;
import org.gradoop.common.model.impl.id.GradoopId;
import org.gradoop.common.model.impl.id.GradoopIdSet;

import java.util.Iterator;

/**
 * Aggregate function that aggregates tuples with a {@link GradoopId} as second field into
 * tuples with a {@link GradoopIdSet} as second fields.
 *
 * @param <T> type of first field
 */

@FunctionAnnotation.ForwardedFields("f0")
public class AggregateGraphs<T>
  implements GroupReduceFunction<Tuple2<T, GradoopId>, Tuple2<T, GradoopIdSet>> {

  /**
   * Reduce object instantiations.
   */
  private Tuple2<T, GradoopIdSet> returnTuple = new Tuple2<>();

  @Override
  public void reduce(Iterable<Tuple2<T, GradoopId>> iterable,
                     Collector<Tuple2<T, GradoopIdSet>> collector) throws Exception {

    if (returnTuple.f1 == null) {
      returnTuple.f1 = new GradoopIdSet();
    }

    Iterator<Tuple2<T, GradoopId>> it = iterable.iterator();
    Tuple2<T, GradoopId> first = it.next();
    returnTuple.f0 = first.f0;
    returnTuple.f1.clear();
    returnTuple.f1.add(first.f1);

    while (it.hasNext()) {
      returnTuple.f1.add(it.next().f1);
    }

    collector.collect(returnTuple);
  }
}
