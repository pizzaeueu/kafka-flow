package com.evolutiongaming.kafka.flow

import cats.data.NonEmptyList
import cats.effect.{Ref, Sync}
import cats.mtl.Stateful
import cats.syntax.all._
import cats.{Applicative, Monad}
import com.evolutiongaming.kafka.flow.effect.CatsEffectMtlInstances._
import com.evolutiongaming.kafka.flow.persistence.Persistence
import com.evolutiongaming.kafka.flow.timer.{ReadTimestamps, TimerFlow}

trait KeyFlow[F[_], E] extends TimerFlow[F] {
  def apply(records: NonEmptyList[E]): F[Unit]
}

object KeyFlow {

  /** Create flow which persists snapshots, events and restores state if needed */
  def of[F[_]: Monad: Ref.Make: KeyContext, S, A](
    fold: FoldOption[F, S, A],
    tick: TickOption[F, S],
    persistence: Persistence[F, S, A],
    timer: TimerFlow[F]
  ): F[KeyFlow[F, A]] = Ref.of[F, Option[S]](none[S]) flatMap { storage =>
    of(storage.stateInstance, fold, tick, persistence, timer)
  }

  def of[F[_]: Monad: Ref.Make: KeyContext, S, A](
    fold: EnhancedFold[F, S, A],
    tick: TickOption[F, S],
    persistence: Persistence[F, S, A],
    additionalPersist: AdditionalStatePersist[F, S, A],
    timer: TimerFlow[F]
  ): F[KeyFlow[F, A]] = Ref.of[F, Option[S]](none[S]) flatMap { storage =>
    of(storage.stateInstance, fold, tick, persistence, additionalPersist, timer)
  }

  /** Create flow which persists snapshots, events and restores state if needed */
  def of[F[_]: Monad: KeyContext, S, A](
    storage: Stateful[F, Option[S]],
    fold: FoldOption[F, S, A],
    tick: TickOption[F, S],
    persistence: Persistence[F, S, A],
    timer: TimerFlow[F]
  ): F[KeyFlow[F, A]] =
    of(storage, EnhancedFold.fromFold(fold), tick, persistence, AdditionalStatePersist.empty[F, S, A], timer)

  def of[F[_]: Monad: KeyContext, S, A](
    storage: Stateful[F, Option[S]],
    fold: EnhancedFold[F, S, A],
    tick: TickOption[F, S],
    persistence: Persistence[F, S, A],
    additionalPersist: AdditionalStatePersist[F, S, A],
    timer: TimerFlow[F]
  ): F[KeyFlow[F, A]] =
    for {
      state <- persistence.read(KeyContext[F].log)
      _ <- storage.set(state)
      // we should not run any timers if there was decision
      // by fold or tick to run the state, because in this
      // case we may flush the key which was already removed
      timerCancelled = storage inspect (_.isEmpty)
      foldToState = FoldToState(storage, fold, persistence, additionalPersist)
      tickToState = TickToState(storage, tick, persistence)
    } yield new KeyFlow[F, A] {
      def apply(records: NonEmptyList[A]): F[Unit] = foldToState(records)
      def onTimer: F[Unit] = tickToState.run *> timerCancelled.ifM(().pure, timer.onTimer)
    }

  /** Does not save anything to the database */
  def transient[F[_]: Sync: KeyContext: ReadTimestamps, K, S, A](
    fold: FoldOption[F, S, A],
    tick: TickOption[F, S],
    timer: TimerFlow[F]
  ): F[KeyFlow[F, A]] =
    for {
      startedAt <- ReadTimestamps[F].current
      _ <- KeyContext[F].hold(startedAt.offset)
      storage <- Ref.of[F, Option[S]](none[S])
      // we should not run any timers if there was decision
      // by fold or tick to run the state, because in this
      // case we may flush the key which was already removed
      timerCancelled = storage.get map (_.isEmpty)
      foldToState = FoldToState(storage.stateInstance, fold, Persistence.empty[F, S, A])
      tickToState = TickToState(storage.stateInstance, tick, Persistence.empty[F, S, A])
    } yield new KeyFlow[F, A] {
      def apply(records: NonEmptyList[A]) = foldToState(records)
      def onTimer = tickToState.run *> timerCancelled.ifM(().pure, timer.onTimer)
    }

  def empty[F[_]: Applicative, A]: KeyFlow[F, A] = new KeyFlow[F, A] {
    def apply(records: NonEmptyList[A]) = ().pure[F]
    def onTimer = ().pure[F]
  }

}
