package com.evolutiongaming.kafka.flow.cassandra

import cats.Monad
import cats.MonadThrow
import cats.arrow.FunctionK
import cats.effect.Clock
import cats.syntax.all._
import com.evolutiongaming.cassandra.sync.CassandraSync
import com.evolutiongaming.kafka.flow.journal.CassandraJournals
import com.evolutiongaming.kafka.flow.key.CassandraKeys
import com.evolutiongaming.kafka.flow.persistence.PersistenceModule
import com.evolutiongaming.kafka.flow.snapshot.CassandraSnapshots
import com.evolutiongaming.kafka.journal.{FromBytes, ToBytes}
import com.evolutiongaming.kafka.journal.eventual.cassandra.{CassandraSession, Segments}

import scala.util.Try

trait CassandraPersistence[F[_], S] extends PersistenceModule[F, S]
object CassandraPersistence {

  /** Creates schema in Cassandra if not there yet
    * Uses a default number of Segments (10000) for keys table.
    */
  @deprecated("Use the alternative with an explicit passing of segments number", since = "0.6.6")
  def withSchemaF[F[_]: MonadThrow: Clock, S](
    session: CassandraSession[F],
    sync: CassandraSync[F],
    consistencyOverrides: ConsistencyOverrides = ConsistencyOverrides.none
  )(implicit
    fromBytes: FromBytes[F, S],
    toBytes: ToBytes[F, S]
  ): F[PersistenceModule[F, S]] =
    withSchemaF(session, sync, consistencyOverrides, CassandraKeys.DefaultSegments)

  /** Creates schema in Cassandra if not there yet. */
  def withSchemaF[F[_]: MonadThrow: Clock, S](
    session: CassandraSession[F],
    sync: CassandraSync[F],
    consistencyOverrides: ConsistencyOverrides,
    keysSegments: Segments
  )(implicit
    fromBytes: FromBytes[F, S],
    toBytes: ToBytes[F, S]
  ): F[PersistenceModule[F, S]] = for {
    _keys <- CassandraKeys.withSchema(session, sync, consistencyOverrides, keysSegments)
    _journals <- CassandraJournals.withSchema(session, sync, consistencyOverrides)
    _snapshots <- CassandraSnapshots.withSchema[F, S](session, sync, consistencyOverrides)
  } yield new CassandraPersistence[F, S] {
    def keys = _keys
    def journals = _journals
    def snapshots = _snapshots
  }

  /** Creates schema in Cassandra if not there yet
    *
    * This method uses the same `JsonCodec[Try]` as `JournalParser` does to
    * simplify defining the basic application.
    * if @consistencyConfig is present then applies
    * ConsistencyConfig.Read for all read queries and
    * ConsistencyConfig.Write for all the mutations
    *
    * Uses a default number of Segments (10000) for keys table.
    */
  @deprecated("Use the alternative with an explicit passing of segments number", since = "0.6.6")
  def withSchema[F[_]: MonadThrow: Clock, S](
    session: CassandraSession[F],
    sync: CassandraSync[F],
    consistencyOverrides: ConsistencyOverrides = ConsistencyOverrides.none
  )(implicit
    fromBytes: FromBytes[Try, S],
    toBytes: ToBytes[Try, S]
  ): F[PersistenceModule[F, S]] = withSchema(session, sync, consistencyOverrides, CassandraKeys.DefaultSegments)

  /** Creates schema in Cassandra if not there yet
    *
    * This method uses the same `JsonCodec[Try]` as `JournalParser` does to
    * simplify defining the basic application.
    * if @consistencyConfig is present then applies
    * ConsistencyConfig.Read for all read queries and
    * ConsistencyConfig.Write for all the mutations
    */
  def withSchema[F[_]: MonadThrow: Clock, S](
    session: CassandraSession[F],
    sync: CassandraSync[F],
    consistencyOverrides: ConsistencyOverrides,
    keysSegments: Segments
  )(implicit
    fromBytes: FromBytes[Try, S],
    toBytes: ToBytes[Try, S]
  ): F[PersistenceModule[F, S]] = {
    val fromTry = FunctionK.liftFunction[Try, F](MonadThrow[F].fromTry)
    implicit val _fromBytes = fromBytes mapK fromTry
    implicit val _toBytes = toBytes mapK fromTry
    withSchemaF(session, sync, consistencyOverrides, keysSegments)
  }

  /** Deletes all data in Cassandra */
  def truncate[F[_]: Monad](
    session: CassandraSession[F],
    sync: CassandraSync[F]
  ): F[Unit] =
    CassandraKeys.truncate(session, sync) *>
      CassandraJournals.truncate(session, sync) *>
      CassandraSnapshots.truncate(session, sync)

}
