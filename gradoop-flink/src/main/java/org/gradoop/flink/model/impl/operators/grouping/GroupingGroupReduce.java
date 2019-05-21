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
package org.gradoop.flink.model.impl.operators.grouping;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.DataSet;
import org.gradoop.common.model.impl.pojo.Edge;
import org.gradoop.common.model.impl.pojo.Vertex;
import org.gradoop.flink.model.impl.epgm.LogicalGraph;
import org.gradoop.flink.model.impl.functions.filters.Not;
import org.gradoop.flink.model.impl.functions.filters.Or;
import org.gradoop.flink.model.impl.operators.grouping.functions.BuildSuperVertex;
import org.gradoop.flink.model.impl.operators.grouping.functions.BuildVertexGroupItem;
import org.gradoop.flink.model.impl.operators.grouping.functions.BuildVertexWithSuperVertex;
import org.gradoop.flink.model.impl.operators.grouping.functions.FilterRegularVertices;
import org.gradoop.flink.model.impl.operators.grouping.functions.FilterSuperVertices;
import org.gradoop.flink.model.impl.operators.grouping.functions.ReduceVertexGroupItems;
import org.gradoop.flink.model.impl.operators.grouping.tuples.EdgeGroupItem;
import org.gradoop.flink.model.impl.operators.grouping.tuples.LabelGroup;
import org.gradoop.flink.model.impl.operators.grouping.tuples.VertexGroupItem;
import org.gradoop.flink.model.impl.operators.grouping.tuples.VertexWithSuperVertex;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Grouping implementation that uses group + groupReduce for building super
 * vertices and updating the original vertices.
 * <p>
 * Algorithmic idea:
 * <p>
 * 1) Map vertices to a minimal representation, i.e. {@link VertexGroupItem}.
 * 2) Group vertices on label and/or property.
 * 3) Create a super vertex id for each group and collect a non-candidate
 * {@link VertexGroupItem} for each group element and one additional
 * super vertex tuple that holds the group aggregate.
 * 4) Filter output of 3)
 * a) non-candidate tuples are mapped to {@link VertexWithSuperVertex}
 * b) super vertex tuples are used to build final super vertices
 * 5) Map edges to a minimal representation, i.e. {@link EdgeGroupItem}
 * 6) Join edges with output of 4a) and replace source/target id with super
 * vertex id.
 * 7) Updated edges are grouped by source and target id and optionally by label
 * and/or edge property.
 * 8) Group combine on the workers and compute aggregate.
 * 9) Group reduce globally and create final super edges.
 */
public class GroupingGroupReduce extends Grouping {

  private final FilterFunction<Vertex> vertexIsInNoGroupFilter;

  /**
   * Creates grouping operator instance.
   *
   * @param useVertexLabels             group on vertex label true/false
   * @param useEdgeLabels               group on edge label true/false
   * @param vertexLabelGroups           stores grouping properties for vertex labels
   * @param edgeLabelGroups             stores grouping properties for edge labels
   * @param retainVerticesWithoutGroups convert vertices that are not member of a group as is to
   *                                    supervertices
   * @param defaultVertexLabelGroup     default vertex label group
   */
  GroupingGroupReduce(boolean useVertexLabels, boolean useEdgeLabels,
    List<LabelGroup> vertexLabelGroups, List<LabelGroup> edgeLabelGroups,
    boolean retainVerticesWithoutGroups,
    LabelGroup defaultVertexLabelGroup) {
    super(useVertexLabels, useEdgeLabels, vertexLabelGroups, edgeLabelGroups,
      retainVerticesWithoutGroups,
      defaultVertexLabelGroup);

    FilterFunction[] isVertexInLabelGroupFunctions = getVertexLabelGroups()
      .parallelStream()
      .map(this::getIsVertexMemberOfLabelGroupFilter)
      .collect(Collectors.toList())
      .toArray(new FilterFunction[getVertexLabelGroups().size()]);

    vertexIsInNoGroupFilter = new Not<>(new Or<Vertex>(isVertexInLabelGroupFunctions));

  }

  @Override
  protected LogicalGraph groupInternal(LogicalGraph graph) {

    DataSet<Vertex> vertices = graph.getVertices();

    Optional<DataSet<Vertex>> optionalConvertedVertices =
      isRetainingVerticesWithoutGroups() ? Optional.of(getVerticesWithoutGroup(vertices)) :
        Optional.empty();

    if (optionalConvertedVertices.isPresent()) {
      vertices = getVerticesToGroup(vertices);
    }

    DataSet<VertexGroupItem> verticesForGrouping = vertices.flatMap(
      new BuildVertexGroupItem(useVertexLabels(), getVertexLabelGroups(), false));

    // group vertices by label / properties / both
    DataSet<VertexGroupItem> vertexGroupItems = groupVertices(verticesForGrouping)
      // apply aggregate function
      .reduceGroup(new ReduceVertexGroupItems(useVertexLabels()));

    DataSet<Vertex> superVertices = vertexGroupItems
      // filter group representative tuples
      .filter(new FilterSuperVertices())
      // build super vertices
      .map(new BuildSuperVertex(useVertexLabels(), config.getVertexFactory()));

    DataSet<VertexWithSuperVertex> vertexToRepresentativeMap = vertexGroupItems
      // filter group element tuples
      .filter(new FilterRegularVertices())
      // build vertex to group representative tuple
      .map(new BuildVertexWithSuperVertex());

    if (optionalConvertedVertices.isPresent()) {

      DataSet<Vertex> convertedVertices = optionalConvertedVertices.get();

      superVertices = superVertices.union(convertedVertices);

      DataSet<VertexWithSuperVertex> optionalRepresentatives =
        convertedVertices.map(new VertexSuperVertexIdentity());

      vertexToRepresentativeMap = vertexToRepresentativeMap.union(optionalRepresentatives);

    }

    // build super edges
    DataSet<Edge> superEdges = buildSuperEdges(graph, vertexToRepresentativeMap);

    return config.getLogicalGraphFactory().fromDataSets(superVertices, superEdges);
  }

  /**
   * Returns a list that contains all vertices that belong to a {@link LabelGroup}.
   *
   * @param vertices to check
   * @return filtered vertices
   */
  private DataSet<Vertex> getVerticesToGroup(DataSet<Vertex> vertices) {
    return vertices.filter(new Not<>(vertexIsInNoGroupFilter));
  }

  /**
   * Returns a list that contains all vertices that don't belong to a {@link LabelGroup}.
   *
   * @param vertices to check
   * @return filtered vertices
   */
  private DataSet<Vertex> getVerticesWithoutGroup(DataSet<Vertex> vertices) {
    return vertices.filter(vertexIsInNoGroupFilter);
  }

  /**
   * Returns a {@link FilterFunction<Vertex>} that checks if a vertex is member of a
   * {@link LabelGroup}.
   *
   * @param group to check
   * @return function
   */
  private FilterFunction<Vertex> getIsVertexMemberOfLabelGroupFilter(LabelGroup group) {

    // Returns true, if a vertex exhibits all properties of a labelGroup.
    // Also returns true, if grouped by properties are empty
    BiFunction<LabelGroup, Vertex, Boolean> hasVertexAllPropertiesOfGroup =
      (BiFunction<LabelGroup, Vertex, Boolean> & Serializable) (labelGroup, vertex) ->
        group.getPropertyKeys()
          .parallelStream()
          .allMatch(vertex::hasProperty);

    boolean groupingByLabels = useVertexLabels();

    // Default case (parameter group is not label specific)
    if (group.getGroupingLabel().equals(Grouping.DEFAULT_VERTEX_LABEL_GROUP)) {

      return (FilterFunction<Vertex>) vertex -> {

        if (groupingByLabels) {

          // a vertex is member of the default group if
          // if its label is not empty or
          //   if the vertex exhibits all grouped by properties

          if (vertex.getLabel().isEmpty()) {
            return !group.getPropertyKeys().isEmpty() && hasVertexAllPropertiesOfGroup.apply(group,
              vertex);
          } else {
            return hasVertexAllPropertiesOfGroup.apply(group,
              vertex);
          }

        } else {

          // if we are not grouping by labels, we need to check if the vertex has all
          // properties grouped by
          // if there is no grouped by property, no vertex should be part of the group

          return !group.getPropertyKeys().isEmpty() && hasVertexAllPropertiesOfGroup.apply(group,
            vertex);
        }
      };
    }

    // Label specific grouping case:
    // a vertex is a member of a label specific group, if the labels match and if the vertex has
    // all of the required properties
    return (FilterFunction<Vertex>) vertex -> vertex.getLabel().equals(group.getGroupingLabel()) &&
      hasVertexAllPropertiesOfGroup.apply(group, vertex);
  }

  /**
   * Creates a {@link VertexWithSuperVertex} with both components referencing the same
   * vertex that is mapped on.
   */
  private class VertexSuperVertexIdentity implements MapFunction<Vertex, VertexWithSuperVertex> {

    /**
     * Avoid object instantiation.
     */
    private final VertexWithSuperVertex reuseTuple;

    private VertexSuperVertexIdentity() {
      reuseTuple = new VertexWithSuperVertex();
    }

    @Override
    public VertexWithSuperVertex map(Vertex value) throws Exception {
      reuseTuple.setVertexId(value.getId());
      reuseTuple.setSuperVertexId((value.getId()));
      return reuseTuple;
    }

  }

}
