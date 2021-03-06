package org.atnos.eff

/**
 * This trait provides a way to rewrite applicative effects
 * when there is an operation allowing the batching of some effects based on the Batchable typeclass
 */
trait Batch {

  def batch[R, T[_], A](eff: Eff[R, A])(implicit batchable: Batchable[T], m: T /= R): Eff[R, A] =
    eff match {
      case ImpureAp(unions, map) =>
        // extract only the effects of type M
        val collected = unions.extract

        // we zip each effect with its indice in the original ImpureAp effect list
        // this allows us to recreate a 'map' function for the rewritten ImpureAp
        // where the result of each effect after interpretation will be at the right place as a argument to the
        // 'map' function
        collected.effects zip collected.indices match {
          case Nil =>  eff

          case e :: rest =>

            // batch any effects which can be batched together
            // by using the Batched datastructure keeping track
            // of both unbatched and batch effects
            val result: Batched[T] = rest.foldLeft(Batched.single(e)) { case (batched, (effect, i)) =>
              batchable.batch(batched.batchedEffect, effect) match {
                case Some(b) => batched.fuse(b, i)
                case None    => batched.append(effect, i)
              }
            }

            result.effects.toList match {
              case Nil =>
                eff

              case e1 :: rest1 =>
                ImpureAp(Unions(m.inject(e1), rest1.map(r => m.inject(r.asInstanceOf[T[Any]])) ++ collected.otherEffects),
                  // the map operation has to reorder the results based on what could be batched or not
                  ls => map(reorder(ls, result.hasBatched, result.keys.toList ++ collected.otherIndices)))
            }
        }

      case _ => eff
    }

  // reorder an input list based on the expected indices for that list
  private def reorder[T[_]](ls: List[Any], hasBatched: Boolean, indices: List[Int])(implicit batchable: Batchable[T]): List[Any] =
    indices.zip(flatten(ls, hasBatched)).sortBy(_._1).map(_._2)

  // the result of batching
  private def flatten[T[_]](ls: List[Any], hasBatch: Boolean)(implicit batchable: Batchable[T]): List[Any] =
    ls match {
      case xs :+ z =>
        xs ++ batchable.distribute(z.asInstanceOf[batchable.Z])

      case Nil =>
        Nil
    }

}

object Batch extends Batch

trait Batchable[T[_]] {
  type Z
  type E
  def distribute(z: Z): List[E]
  def batch[X, Y](t1: T[X], t2: T[Y]): Option[T[Z]]
}

/**
 * The Batched classes are used to store unbatched and batched effects
 * depending on the result of the Batchable typeclass
 *
 * The assumption is that the order of the effects in 'effects'
 * correspond to the order of the keys in 'keys'
 *
 */
private sealed trait Batched[T[_]] {
  def effects: Vector[T[_]]
  def keys: Vector[Int]
  def batchedEffect: T[_]
  def hasBatched: Boolean

  def append(ty: T[_], key: Int): Batched[T]
  def fuse(ty: T[_], key: Int): Batched[T]
}

private object Batched {
  def single[T[_], X](txi: (T[X], Int)): Batched[T] =
    Single(txi._1, Vector(txi._2))
}

private case class Composed[T[_]](unbatched: Vector[Batched[T]], batched: Single[T]) extends Batched[T] {
  def effects = unbatched.flatMap(_.effects)
  def keys = unbatched.flatMap(_.keys) ++ batched.keys
  def batchedEffect: T[_] = batched.batchedEffect
  def hasBatched: Boolean = batched.hasBatched

  def append(ty: T[_], key: Int) =
    copy(unbatched  = unbatched :+ Batched.single((ty, key)))

  def fuse(ty: T[_], key: Int) =
    copy(batched = Single(ty, batched.keys :+ key))
}

private case class Single[T[_]](tx: T[_], keys: Vector[Int]) extends Batched[T] {
  def effects = Vector(tx)
  def batchedEffect = tx
  def hasBatched: Boolean = keys.size > 1

  def append(ty: T[_], key: Int): Batched[T] =
    Composed(Vector(Batched.single((tx, key))), this)

  def fuse(ty: T[_], key: Int): Batched[T] =
    Single(ty, keys :+ key)
}

