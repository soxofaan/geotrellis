/*
 * Copyright 2016 Azavea
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

package geotrellis.spark.io.accumulo

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.avro._
import geotrellis.util._

import org.apache.hadoop.io.Text
import org.apache.spark.SparkContext
import org.apache.accumulo.core.data.{Range => AccumuloRange}
import spray.json._

import scala.reflect._

class AccumuloLayerReader(val attributeStore: AttributeStore)(implicit sc: SparkContext, instance: AccumuloInstance)
    extends FilteringLayerReader[LayerId] {

  val defaultNumPartitions = sc.defaultParallelism

  def read[
    K: AvroRecordCodec: Boundable: JsonFormat: ClassTag,
    V: AvroRecordCodec: ClassTag,
    M: JsonFormat: GetComponent[?, Bounds[K]]
  ](id: LayerId, tileQuery: LayerQuery[K, M], numPartitions: Int, filterIndexOnly: Boolean) = {
    if (!attributeStore.layerExists(id)) throw new LayerNotFoundError(id)

    val LayerAttributes(header, metadata, keyIndex, writerSchema) = try {
      attributeStore.readLayerAttributes[AccumuloLayerHeader, M, K](id)
    } catch {
      case e: AttributeNotFoundError => throw new LayerReadError(id).initCause(e)
    }

    val queryKeyBounds = tileQuery(metadata)

    val decompose = (bounds: KeyBounds[K]) => {
      if(queryKeyBounds.size == 1 && queryKeyBounds.head == bounds) Seq(new AccumuloRange())
      else keyIndex.indexRanges(bounds).map { case (min, max) =>
        new AccumuloRange(new Text(AccumuloKeyEncoder.long2Bytes(min)), new Text(AccumuloKeyEncoder.long2Bytes(max)))
      }
    }

    val rdd = AccumuloRDDReader.read[K, V](header.tileTable, columnFamily(id), queryKeyBounds, decompose, filterIndexOnly, Some(writerSchema))
    new ContextRDD(rdd, metadata)
  }
}

object AccumuloLayerReader {
  def apply(instance: AccumuloInstance)(implicit sc: SparkContext): AccumuloLayerReader =
    new AccumuloLayerReader(AccumuloAttributeStore(instance.connector))(sc, instance)

  def apply(attributeStore: AccumuloAttributeStore)(implicit sc: SparkContext, instance: AccumuloInstance): AccumuloLayerReader =
    new AccumuloLayerReader(attributeStore)

  def apply()(implicit sc: SparkContext, instance: AccumuloInstance): AccumuloLayerReader =
    new AccumuloLayerReader(AccumuloAttributeStore(instance.connector))
}
