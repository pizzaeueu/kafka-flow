package com.evolutiongaming.kafka.flow

import PartitionFlowMetrics._
import TopicFlowMetrics._
import cats.Monad
import cats.effect.Resource
import com.evolutiongaming.kafka.flow.KeyStateMetrics._
import com.evolutiongaming.kafka.flow.journal.JournalDatabase
import com.evolutiongaming.kafka.flow.journal.JournalDatabaseMetrics._
import com.evolutiongaming.kafka.flow.key.KeyDatabase
import com.evolutiongaming.kafka.flow.key.KeyDatabaseMetrics._
import com.evolutiongaming.kafka.flow.metrics.Metrics
import com.evolutiongaming.kafka.flow.metrics.MetricsK
import com.evolutiongaming.kafka.flow.persistence.PersistenceModule
import com.evolutiongaming.kafka.flow.snapshot.SnapshotDatabase
import com.evolutiongaming.kafka.flow.snapshot.SnapshotDatabaseMetrics._
import com.evolutiongaming.kafka.flow.timer.TimerDatabase
import com.evolutiongaming.kafka.flow.timer.TimerDatabaseMetrics._
import com.evolutiongaming.kafka.journal.ConsRecord
import com.evolutiongaming.smetrics.CollectorRegistry
import com.evolutiongaming.smetrics.MeasureDuration

trait FlowMetrics[F[_]] {

  implicit def keyDatabaseMetrics: Metrics[KeyDatabase[F, KafkaKey]]
  implicit def journalDatabaseMetrics: Metrics[JournalDatabase[F, KafkaKey, ConsRecord]]
  implicit def snapshotDatabaseMetrics: MetricsK[SnapshotDatabase[F, KafkaKey, *]]
  implicit def timerDatabaseMetrics: MetricsK[TimerDatabase[F, KafkaKey, *]]
  implicit def persistenceModuleMetrics: MetricsK[PersistenceModule[F, *]]
  implicit def foldOptionMetrics: MetricsK[FoldOption[F, *, ConsRecord]]
  implicit def enhancedFoldMetrics: MetricsK[EnhancedFold[F, *, ConsRecord]]
  implicit def keyStateOfMetrics: Metrics[KeyStateOf[F]]
  implicit def partitionFlowOfMetrics: Metrics[PartitionFlowOf[F]]
  implicit def topicFlowOfMetrics: Metrics[TopicFlowOf[F]]

}
object FlowMetrics {

  def apply[F[_]](implicit F: FlowMetrics[F]): FlowMetrics[F] = F

  def of[F[_]: Monad: MeasureDuration](registry: CollectorRegistry[F]): Resource[F, FlowMetrics[F]] = for {
    keyDatabase <- keyDatabaseMetricsOf[F].apply(registry)
    journalDatabase <- journalDatabaseMetricsOf[F].apply(registry)
    snapshotDatabase <- snapshotDatabaseMetricsOf[F].apply(registry)
    timerDatabase <- timerDatabaseMetricsKOf[F].apply(registry)
    persistenceModule = new MetricsK[PersistenceModule[F, *]] {
      def withMetrics[S](module: PersistenceModule[F, S]) = new PersistenceModule[F, S] {
        def keys = keyDatabase.withMetrics(module.keys)
        def journals = journalDatabase.withMetrics(module.journals)
        def snapshots = snapshotDatabase.withMetrics(module.snapshots)
      }
    }
    foldMetrics <- FoldMetrics.of[F](registry)
    keyStateOf <- keyStateOfMetricsOf[F].apply(registry)
    partitionFlowOf <- partitionFlowOfMetricsOf[F].apply(registry)
    topicFlowOf <- topicFlowOfMetricsOf[F].apply(registry)
  } yield new FlowMetrics[F] {
    def keyDatabaseMetrics = keyDatabase
    def journalDatabaseMetrics = journalDatabase
    def snapshotDatabaseMetrics = snapshotDatabase
    def timerDatabaseMetrics = timerDatabase
    def persistenceModuleMetrics = persistenceModule
    def foldOptionMetrics = foldMetrics.foldOptionMetrics
    def enhancedFoldMetrics = foldMetrics.enhancedFoldMetrics
    def keyStateOfMetrics = keyStateOf
    def partitionFlowOfMetrics = partitionFlowOf
    def topicFlowOfMetrics = topicFlowOf
  }

  def empty[F[_]]: FlowMetrics[F] = new FlowMetrics[F] {
    def keyDatabaseMetrics = Metrics.empty
    def journalDatabaseMetrics = Metrics.empty
    def snapshotDatabaseMetrics = MetricsK.empty[SnapshotDatabase[F, KafkaKey, *]]
    def timerDatabaseMetrics = MetricsK.empty[TimerDatabase[F, KafkaKey, *]]
    def persistenceModuleMetrics = MetricsK.empty[PersistenceModule[F, *]]
    def foldOptionMetrics = MetricsK.empty[FoldOption[F, *, ConsRecord]]
    def enhancedFoldMetrics = MetricsK.empty[EnhancedFold[F, *, ConsRecord]]
    def keyStateOfMetrics = Metrics.empty
    def partitionFlowOfMetrics = Metrics.empty
    def topicFlowOfMetrics = Metrics.empty
  }

}
