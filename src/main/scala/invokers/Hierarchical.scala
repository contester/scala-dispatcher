package org.stingray.contester.invokers

import org.apache.commons.collections.MultiMap
import com.twitter.util.Future

/*
Hierarchical invoker registry.

Set[Features] -> Invoker(capacity)
get(f) - list of invokers by capacity. Special case - capacity=0

dimensions Map[Property, Rational]
 */

trait Feature
trait Dimension

trait SetTrie[A, B] extends Map[Set[A], B]

trait HierarchicalScheduler {
  val invokers: MultiMap[Set[Feature], NormalInvoker]
  val fullInvokers: Set[NormalInvoker]

  def apply(features: Set[Feature], exclusive: Boolean, dimensions: Map[Dimension, Rational]): Future[]

}