/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.graph.streaming;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.functions.RichReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Vertex;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * Represents a streamed graph consisting of {@link org.apache.flink.graph.Edge edges}
 * and {@link org.apache.flink.graph.Vertex vertices}.
 *
 * @see org.apache.flink.graph.Edge
 * @see org.apache.flink.graph.Vertex
 *
 * @param <K> the key type for edge and vertex identifiers.
 * @param <VV> the value type for vertexes.
 * @param <EV> the value type for edges.
 */
public class GraphStream<K extends Comparable<K> & Serializable, VV extends Serializable, EV extends Serializable> {

	private final StreamExecutionEnvironment context;
	private final DataStream<Vertex<K, VV>> vertices;
	private final DataStream<Edge<K, EV>> edges;

	/**
	 * Creates a graph from two DataStreams: vertices and edges
	 *
	 * @param vertices a DataStream of vertices.
	 * @param edges a DataStream of edges.
	 * @param context the flink execution environment.
	 */
	public GraphStream(DataStream<Vertex<K, VV>> vertices, DataStream<Edge<K, EV>> edges,
			StreamExecutionEnvironment context) {
		this.vertices = vertices;
		this.edges = edges;
		this.context = context;
	}

	/**
	 * Creates a GraphStream from a DataStream of vertices and a DataStream of edges.
	 *
	 * @param vertices a DataStream of vertices.
	 * @param edges a DataStream of edges.
	 * @param context the flink execution environment.
	 * @return the newly created GraphStream.
	 */
	public static <K extends Comparable<K> & Serializable, VV extends Serializable, EV extends Serializable>
			GraphStream<K, VV, EV> fromDataStream(DataStream<Vertex<K, VV>> vertices, DataStream<Edge<K, EV>> edges,
			StreamExecutionEnvironment context) {
		return new GraphStream<>(vertices, edges, context);
	}

	/**
	 * Creates a GraphStream from a DataStream of vertices and a DataStream of edges.
	 *
	 * @param edges a DataStream of edges.
	 * @param context the flink execution environment.
	 * @return the newly created GraphStream.
	 */
	public static <K extends Comparable<K> & Serializable, EV extends Serializable> GraphStream<K, NullValue, EV>
			fromDataStream(DataStream<Edge<K, EV>> edges, StreamExecutionEnvironment context) {

		DataStream<Vertex<K, NullValue>> vertices = edges
				.flatMap(new EmitSrcAndTarget<K, EV>())
				.groupBy(0)
				.filter(new FilterDistinctVertices());

		return new GraphStream<>(vertices, edges, context);
	}

	private static final class EmitSrcAndTarget<K extends Comparable<K> & Serializable, EV extends Serializable>
			implements FlatMapFunction<Edge<K, EV>, Vertex<K, NullValue>> {

		@Override
		public void flatMap(Edge<K, EV> edge, Collector<Vertex<K, NullValue>> out) throws Exception {

			out.collect(new Vertex<>(edge.getSource(), NullValue.getInstance()));
			out.collect(new Vertex<>(edge.getTarget(), NullValue.getInstance()));
		}
	}

	private static final class FilterDistinctVertices<K extends Comparable<K> & Serializable>
			implements FilterFunction<Vertex<K, NullValue>> {

		Set<K> keys = new HashSet<>();

		@Override
		public boolean filter(Vertex<K, NullValue> vertex) throws Exception {
			if (!keys.contains(vertex.getId())) {
				keys.add(vertex.getId());
				return true;
			}
			return false;
		}
	}

	/**
	 * @return the flink streaming execution environment.
	 */
	public StreamExecutionEnvironment getContext() {
		return this.context;
	}

	/**
	 * @return the vertex DataStream.
	 */
	public DataStream<Vertex<K, VV>> getVertices() {
		return this.vertices;
	}

	/**
	 * @return the edge DataStream.
	 */
	public DataStream<Edge<K, EV>> getEdges() {
		return this.edges;
	}

	/**
	 * Apply a function to the attribute of each vertex in the graph stream.
	 *
	 * @param mapper the map function to apply.
	 * @return a new graph stream.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <NV extends Serializable> GraphStream<K, NV, EV> mapVertices(final MapFunction<Vertex<K, VV>, NV> mapper) {

		TypeInformation<K> keyType = ((TupleTypeInfo<?>) vertices.getType()).getTypeAt(0);
		TypeInformation<NV> valueType = TypeExtractor.createTypeInfo(MapFunction.class, mapper.getClass(), 1,
				null, null);

		TypeInformation<Vertex<K, NV>> returnType = (TypeInformation<Vertex<K, NV>>) new TupleTypeInfo(
				Vertex.class, keyType, valueType);

		DataStream<Vertex<K, NV>> mappedVertices = vertices.map(
				new MapFunction<Vertex<K, VV>, Vertex<K, NV>>() {
					public Vertex<K, NV> map(Vertex<K, VV> value) throws Exception {
						return new Vertex<>(value.f0, mapper.map(value));
					}
				}).setType(returnType);

		return new GraphStream<>(mappedVertices, this.edges, this.context);
	}

	/**
	 * Apply a function to the attribute of each edge in the graph stream.
	 *
	 * @param mapper the map function to apply.
	 * @return a new graph stream.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <NV extends Serializable> GraphStream<K, VV, NV> mapEdges(final MapFunction<Edge<K, EV>, NV> mapper) {

		TypeInformation<K> keyType = ((TupleTypeInfo<?>) edges.getType()).getTypeAt(0);
		TypeInformation<NV> valueType = TypeExtractor.createTypeInfo(MapFunction.class, mapper.getClass(), 1, null, null);

		TypeInformation<Edge<K, NV>> returnType = (TypeInformation<Edge<K, NV>>) new TupleTypeInfo(
				Edge.class, keyType, keyType, valueType);

		DataStream<Edge<K, NV>> mappedEdges = edges.map(
				new MapFunction<Edge<K, EV>, Edge<K, NV>>() {
					public Edge<K, NV> map(Edge<K, EV> value) throws Exception {
						return new Edge<>(value.f0, value.f1, mapper
								.map(value));
					}
				}).setType(returnType);

		return new GraphStream<>(this.vertices, mappedEdges, this.context);
	}
}
