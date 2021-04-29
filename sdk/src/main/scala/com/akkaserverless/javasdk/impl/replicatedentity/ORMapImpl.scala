/*
 * Copyright 2019 Lightbend Inc.
 */

package com.akkaserverless.javasdk.impl.replicatedentity

import com.akkaserverless.javasdk.replicatedentity.{ORMap, ReplicatedDataFactory}
import com.akkaserverless.javasdk.impl.AnySupport
import com.akkaserverless.protocol.replicated_entity.{ORMapDelta, ORMapEntryDelta, ReplicatedEntityDelta}
import com.google.protobuf.any.{Any => ScalaPbAny}
import org.slf4j.LoggerFactory

import java.util
import java.util.{function, Map}
import scala.collection.JavaConverters._

private object ORMapImpl {
  private val log = LoggerFactory.getLogger(classOf[ORMapImpl[_, _]])
}

/**
 * A few notes on implementation:
 *
 * - put, and any similar operations (such as Map.Entry.setValue) are not supported, because the only way to create a
 *   Replicated Data object is using a ReplicatedDataFactory, and we only make ReplicatedDataFactory available in very
 *   specific contexts, such as in the getOrCreate method. The getOrCreate method is the only way to insert something
 *   new into the map.
 * - All mechanisms for removal are supported - eg, calling remove directly, calling remove on any of the derived sets
 *   (entrySet, keySet, values), and calling remove on the entrySet iterator.
 * - ju.AbstractMap is very useful, though bases most of its implementation on entrySet, so we need to take care to
 *   efficiently implement operations that it implements in O(n) time that we can do in O(1) time, such as
 *   get/remove/containsKey.
 */
private[replicatedentity] final class ORMapImpl[K, V <: InternalReplicatedData](anySupport: AnySupport)
    extends util.AbstractMap[K, V]
    with InternalReplicatedData
    with ORMap[K, V] {
  import ORMapImpl.log

  override final val name = "ORMap"
  private val value = new util.HashMap[K, V]()
  private val added = new util.HashMap[K, (ScalaPbAny, V)]()
  private val removed = new util.HashSet[ScalaPbAny]()
  private var cleared = false

  override def getOrCreate(key: K, create: function.Function[ReplicatedDataFactory, V]): V =
    if (value.containsKey(key)) {
      value.get(key)
    } else {
      val encodedKey = anySupport.encodeScala(key)
      var internalData: InternalReplicatedData = null
      val data = create(new AbstractReplicatedEntityFactory {
        override protected def anySupport: AnySupport = ORMapImpl.this.anySupport
        override protected def newEntity[C <: InternalReplicatedData](entity: C): C = {
          if (internalData != null) {
            throw new IllegalStateException(
              "getOrCreate creation callback must only be used to create one replicated data item at a time"
            )
          }
          internalData = entity
          entity
        }
      })
      if (data == null) {
        throw new IllegalArgumentException("getOrCreate creation callback must return a Replicated Data object")
      } else if (data != internalData) {
        throw new IllegalArgumentException(
          "Replicated Data returned by getOrCreate creation callback must have been created by the ReplicatedDataFactory passed to it"
        )
      }

      value.put(key, data)
      added.put(key, (encodedKey, data))
      data
    }

  override def containsKey(key: Any): Boolean = value.containsKey(key)

  override def get(key: Any): V = value.get(key)

  override def put(key: K, value: V): V =
    throw new UnsupportedOperationException("Cannot put on an ORMap, use getOrCreate instead.")

  override def remove(key: Any): V = {
    if (value.containsKey(key)) {
      val encodedKey = anySupport.encodeScala(key)
      if (added.containsKey(key)) {
        added.remove(key)
      } else {
        removed.add(encodedKey)
      }
    }
    value.remove(key)
  }

  // Most methods in AbstractMap build on this. Most important thing is to get the mutability aspects right.
  override def entrySet(): util.Set[util.Map.Entry[K, V]] = new EntrySet

  private class EntrySet extends util.AbstractSet[util.Map.Entry[K, V]] {
    override def size(): Int = value.size()
    override def iterator(): util.Iterator[util.Map.Entry[K, V]] = new util.Iterator[util.Map.Entry[K, V]] {
      private val iter = value.entrySet().iterator()
      private var lastNext: util.Map.Entry[K, V] = _
      override def hasNext: Boolean = iter.hasNext
      override def next(): Map.Entry[K, V] = {
        lastNext = iter.next()
        new util.Map.Entry[K, V] {
          private val entry = lastNext
          override def getKey: K = entry.getKey
          override def getValue: V = entry.getValue
          override def setValue(value: V): V = throw new UnsupportedOperationException()
        }
      }
      override def remove(): Unit = {
        if (lastNext != null) {
          val encodedKey = anySupport.encodeScala(lastNext.getKey)
          if (added.containsKey(lastNext.getKey)) {
            added.remove(lastNext.getKey)
          } else {
            removed.add(encodedKey)
          }
        }
        iter.remove()
      }
    }
    override def clear(): Unit = ORMapImpl.this.clear()
  }

  override def size(): Int = value.size()

  override def isEmpty: Boolean = super.isEmpty

  override def clear(): Unit = {
    value.clear()
    cleared = true
    removed.clear()
    added.clear()
  }

  override def hasDelta: Boolean =
    if (cleared || !added.isEmpty || !removed.isEmpty) {
      true
    } else {
      value.values().asScala.exists(_.hasDelta)
    }

  override def delta: ReplicatedEntityDelta.Delta = {
    val updated = (value.asScala -- this.added.keySet().asScala).collect {
      case (key, changed) if changed.hasDelta =>
        ORMapEntryDelta(Some(anySupport.encodeScala(key)), Some(ReplicatedEntityDelta(changed.delta)))
    }
    val added = this.added.asScala.values.map {
      case (key, value) => ORMapEntryDelta(Some(key), Some(ReplicatedEntityDelta(value.delta)))
    }

    ReplicatedEntityDelta.Delta.Ormap(
      ORMapDelta(
        cleared = cleared,
        removed = removed.asScala.toVector,
        updated = updated.toVector,
        added = added.toVector
      )
    )
  }

  override def resetDelta(): Unit = {
    cleared = false
    added.clear()
    removed.clear()
    value.values().asScala.foreach(_.resetDelta())
  }

  override val applyDelta = {
    case ReplicatedEntityDelta.Delta.Ormap(ORMapDelta(cleared, removed, updated, added, _)) =>
      if (cleared) {
        value.clear()
      }
      removed.foreach(key => value.remove(anySupport.decode(key)))
      updated.foreach {
        case ORMapEntryDelta(Some(key), Some(delta), _) =>
          val data = value.get(anySupport.decode(key))
          if (data == null) {
            log.warn("ORMap entry to update with key [{}] not found in map", key)
          } else {
            data.applyDelta(delta.delta)
          }
      }
      added.foreach {
        case ORMapEntryDelta(Some(key), Some(delta), _) =>
          value.put(anySupport.decode(key).asInstanceOf[K],
                    ReplicatedEntityDeltaTransformer.create(delta, anySupport).asInstanceOf[V])
      }
  }

  override def toString = s"ORMap(${value.asScala.map { case (k, v) => s"$k->$v" }.mkString(",")})"
}