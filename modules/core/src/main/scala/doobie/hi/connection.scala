// Copyright (c) 2013-2017 Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.hi

import doobie.enum.Holdability
import doobie.enum.ResultSetType
import doobie.enum.ResultSetConcurrency
import doobie.enum.TransactionIsolation
import doobie.enum.AutoGeneratedKeys
import doobie.syntax.monaderror._
import doobie.util.Read
import doobie.util.analysis.Analysis
import doobie.util.composite.Composite
import doobie.util.stream.repeatEvalChunks

import java.sql.{ Savepoint, PreparedStatement, ResultSet }

import scala.collection.immutable.Map
import scala.collection.JavaConverters._

import cats.Foldable
import cats.implicits._
import fs2.Stream
import fs2.Stream.{ eval, bracket }

/**
 * Module of high-level constructors for `ConnectionIO` actions.
 * @group Modules
 */
@SuppressWarnings(Array("org.wartremover.warts.Overloading"))
object connection {
  import implicits._

  /** @group Lifting */
  def delay[A](a: => A): ConnectionIO[A] =
    FC.delay(a)

  private def liftStream[A: Read](
    chunkSize: Int,
    create: ConnectionIO[PreparedStatement],
    prep:   PreparedStatementIO[Unit],
    exec:   PreparedStatementIO[ResultSet]): Stream[ConnectionIO, A] = {

    def prepared(ps: PreparedStatement): Stream[ConnectionIO, PreparedStatement] =
      eval[ConnectionIO, PreparedStatement] {
        val fs = FPS.setFetchSize(chunkSize)
        FC.embed(ps, fs *> prep).map(_ => ps)
      }

    def unrolled(rs: ResultSet): Stream[ConnectionIO, A] =
      repeatEvalChunks(FC.embed(rs, resultset.getNextChunk[A](chunkSize)))

    val preparedStatement: Stream[ConnectionIO, PreparedStatement] =
      bracket(create)(prepared, FC.embed(_, FPS.close))

    def results(ps: PreparedStatement): Stream[ConnectionIO, A] =
      bracket(FC.embed(ps, exec))(unrolled, FC.embed(_, FRS.close))

    preparedStatement.flatMap(results)

  }

  /**
   * Construct a prepared statement from the given `sql`, configure it with the given `PreparedStatementIO`
   * action, and return results via a `Stream`.
   * @group Prepared Statements
   */
  def process[A: Read](sql: String, prep: PreparedStatementIO[Unit], chunkSize: Int): Stream[ConnectionIO, A] =
    liftStream(chunkSize, FC.prepareStatement(sql), prep, FPS.executeQuery)

  /**
   * Construct a prepared update statement with the given return columns (and composite destination
   * type `A`) and sql source, configure it with the given `PreparedStatementIO` action, and return
   * the generated key results via a
   * `Stream`.
   * @group Prepared Statements
   */
  def updateWithGeneratedKeys[A: Read](cols: List[String])(sql: String, prep: PreparedStatementIO[Unit], chunkSize: Int): Stream[ConnectionIO, A] =
    liftStream(chunkSize, FC.prepareStatement(sql, cols.toArray), prep, FPS.executeUpdate *> FPS.getGeneratedKeys)

  /** @group Prepared Statements */
  def updateManyWithGeneratedKeys[F[_]: Foldable, A: Composite, B: Read](cols: List[String])(sql: String, prep: PreparedStatementIO[Unit], fa: F[A], chunkSize: Int): Stream[ConnectionIO, B] =
    liftStream[B](chunkSize, FC.prepareStatement(sql, cols.toArray), prep, HPS.addBatchesAndExecute(fa) *> FPS.getGeneratedKeys)

  /** @group Transaction Control */
  val commit: ConnectionIO[Unit] =
    FC.commit

  /**
   * Construct an analysis for the provided `sql` query, given parameter composite type `A` and
   * resultset row composite `B`.
   */
  def prepareQueryAnalysis[A: Composite, B: Read](sql: String): ConnectionIO[Analysis] =
    prepareStatement(sql) {
      (HPS.getParameterMappings[A], HPS.getColumnMappings[B]) mapN (Analysis(sql, _, _))
    }

  def prepareQueryAnalysis0[B: Read](sql: String): ConnectionIO[Analysis] =
    prepareStatement(sql) {
      HPS.getColumnMappings[B] map (cm => Analysis(sql, Nil, cm))
    }

  def prepareUpdateAnalysis[A: Composite](sql: String): ConnectionIO[Analysis] =
    prepareStatement(sql) {
      HPS.getParameterMappings[A] map (pm => Analysis(sql, pm, Nil))
    }

  def prepareUpdateAnalysis0(sql: String): ConnectionIO[Analysis] =
    prepareStatement(sql) {
      Analysis(sql, Nil, Nil).pure[PreparedStatementIO]
    }


  /** @group Statements */
  def createStatement[A](k: StatementIO[A]): ConnectionIO[A] =
    FC.createStatement.flatMap(s => FC.embed(s, k guarantee FS.close))

  /** @group Statements */
  def createStatement[A](rst: ResultSetType, rsc: ResultSetConcurrency)(k: StatementIO[A]): ConnectionIO[A] =
    FC.createStatement(rst.toInt, rsc.toInt).flatMap(s => FC.embed(s, k guarantee FS.close))

  /** @group Statements */
  def createStatement[A](rst: ResultSetType, rsc: ResultSetConcurrency, rsh: Holdability)(k: StatementIO[A]): ConnectionIO[A] =
    FC.createStatement(rst.toInt, rsc.toInt, rsh.toInt).flatMap(s => FC.embed(s, k guarantee FS.close))

  /** @group Connection Properties */
  val getCatalog: ConnectionIO[String] =
    FC.getCatalog

  /** @group Connection Properties */
  def getClientInfo(key: String): ConnectionIO[Option[String]] =
    FC.getClientInfo(key).map(Option(_))

  /** @group Connection Properties */
  val getClientInfo: ConnectionIO[Map[String, String]] =
    FC.getClientInfo.map(_.asScala.toMap)

  /** @group Connection Properties */
  val getHoldability: ConnectionIO[Holdability] =
    FC.getHoldability.map(Holdability.unsafeFromInt)

  /** @group Connection Properties */
  def getMetaData[A](k: DatabaseMetaDataIO[A]): ConnectionIO[A] =
    FC.getMetaData.flatMap(s => FC.embed(s, k))

  /** @group Transaction Control */
  val getTransactionIsolation: ConnectionIO[TransactionIsolation] =
    FC.getTransactionIsolation.map(TransactionIsolation.unsafeFromInt)

  /** @group Connection Properties */
  val isReadOnly: ConnectionIO[Boolean] =
    FC.isReadOnly

  /** @group Callable Statements */
  def prepareCall[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency)(k: CallableStatementIO[A]): ConnectionIO[A] =
    FC.prepareCall(sql, rst.toInt, rsc.toInt).flatMap(s => FC.embed(s, k guarantee FCS.close))

  /** @group Callable Statements */
  def prepareCall[A](sql: String)(k: CallableStatementIO[A]): ConnectionIO[A] =
    FC.prepareCall(sql).flatMap(s => FC.embed(s, k guarantee FCS.close))

  /** @group Callable Statements */
  def prepareCall[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency, rsh: Holdability)(k: CallableStatementIO[A]): ConnectionIO[A] =
    FC.prepareCall(sql, rst.toInt, rsc.toInt, rsh.toInt).flatMap(s => FC.embed(s, k guarantee FCS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, rst.toInt, rsc.toInt).flatMap(s => FC.embed(s, k guarantee FPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql).flatMap(s => FC.embed(s, k guarantee FPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, rst: ResultSetType, rsc: ResultSetConcurrency, rsh: Holdability)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, rst.toInt, rsc.toInt, rsh.toInt).flatMap(s => FC.embed(s, k guarantee FPS.close))

  /** @group Prepared Statements */
  def prepareStatement[A](sql: String, agk: AutoGeneratedKeys)(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, agk.toInt).flatMap(s => FC.embed(s, k guarantee FPS.close))

  /** @group Prepared Statements */
  def prepareStatementI[A](sql: String, columnIndexes: List[Int])(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, columnIndexes.toArray).flatMap(s => FC.embed(s, k guarantee FPS.close))

  /** @group Prepared Statements */
  def prepareStatementS[A](sql: String, columnNames: List[String])(k: PreparedStatementIO[A]): ConnectionIO[A] =
    FC.prepareStatement(sql, columnNames.toArray).flatMap(s => FC.embed(s, k guarantee FPS.close))

  /** @group Transaction Control */
  def releaseSavepoint(sp: Savepoint): ConnectionIO[Unit] =
    FC.releaseSavepoint(sp)

  /** @group Transaction Control */
  def rollback(sp: Savepoint): ConnectionIO[Unit] =
    FC.rollback(sp)

  /** @group Transaction Control */
  val rollback: ConnectionIO[Unit] =
    FC.rollback

  /** @group Connection Properties */
  def setCatalog(catalog: String): ConnectionIO[Unit] =
    FC.setCatalog(catalog)

  /** @group Connection Properties */
  def setClientInfo(key: String, value: String): ConnectionIO[Unit] =
    FC.setClientInfo(key, value)

  /** @group Connection Properties */
  def setClientInfo(info: Map[String, String]): ConnectionIO[Unit] =
    FC.setClientInfo {
      val ps = new java.util.Properties
      ps.putAll(info.asJava)
      ps
    }

  /** @group Connection Properties */
  def setHoldability(h: Holdability): ConnectionIO[Unit] =
    FC.setHoldability(h.toInt)

  /** @group Connection Properties */
  def setReadOnly(readOnly: Boolean): ConnectionIO[Unit] =
    FC.setReadOnly(readOnly)

  /** @group Transaction Control */
  val setSavepoint: ConnectionIO[Savepoint] =
    FC.setSavepoint

  /** @group Transaction Control */
  def setSavepoint(name: String): ConnectionIO[Savepoint] =
    FC.setSavepoint(name)

  /** @group Transaction Control */
  def setTransactionIsolation(ti: TransactionIsolation): ConnectionIO[Unit] =
    FC.setTransactionIsolation(ti.toInt)

  // /**
  //  * Compute a map from native type to closest-matching JDBC type.
  //  * @group MetaData
  //  */
  // val nativeTypeMap: ConnectionIO[Map[String, JdbcType]] = {
  //   getMetaData(FDMD.getTypeInfo.flatMap(FDMD.embed(_, HRS.list[(String, JdbcType)].map(_.toMap))))
  // }
}
