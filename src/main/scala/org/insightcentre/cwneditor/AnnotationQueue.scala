package org.insightcentre.cwneditor

import java.sql._
import sql._
import java.io.File

trait AnnotationQueue {
  def get(id : Int) : Option[AnnotationQueueEntry]
  def getQueue(user : String) : List[AnnotationQueueEntry]
  def pullQueue(user : String, n : Int) : Unit
  def remove(user : String, id : Int) : Boolean
  def dequeue(user : String, id : Int) : Boolean
  def extend(user : String, id : Int) : Boolean
}

case class AnnotationQueueEntry(id : Int, expiry : Long, lemma : String, user : String, examples : List[String]) {
  def expiryString = AnnotationQueueEntry.dateFormat.format(new java.util.Date(expiry))

  def toEntry : Entry = Entry(lemma, examples.map({e => Example(e)}),
    "", Nil, Entrys.CWN_NEW + id)
}

object AnnotationQueueEntry {
  val dateFormat = new java.text.SimpleDateFormat("E d MMMM YYYY")
}

object SQLAnnotationQueue extends AnnotationQueue {
  val HOLD_LENGTH_DAYS = 7

  try {
    Class.forName("org.sqlite.JDBC") }
  catch {
    case x : ClassNotFoundException => throw new RuntimeException("No Database Driver", x) }

  private val file = new File("queue.db")
  def conn = {
    DriverManager.getConnection("jdbc:sqlite:" + file.getPath())
  }

  if(!file.exists) {
    withSession(conn) { implicit sesion =>
      sql"""CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT,
                                expiry INTEGER,
                                lemma TEXT,
                                user TEXT,
                                examples TEXT)""".execute
      sql"""CREATE INDEX queue_users ON queue (user)""".execute
    }
  }

  def get(id : Int) = withSession(conn) { implicit session =>
    sql"""SELECT * FROM queue WHERE id=$id""".as5[Int,Long,String,String,String].map({
      case (id, expiry, lemma, user, examples) => AnnotationQueueEntry(id, expiry, lemma, user, examples.split(";;;").toList)
    }).headOption
  }


  def getQueue(user : String) = withSession(conn) { implicit session =>
    val now = (java.time.LocalDate.now().toEpochDay()) * 86400000l 
    sql"""SELECT * FROM queue WHERE user=$user AND expiry > $now""".as5[Int,Long,String,String,String].map({
      case (id, expiry, lemma, user, examples) => AnnotationQueueEntry(id, expiry, lemma, user, examples.split(";;;").toList)
    }).toList
  }

  def pullQueue(user : String, n : Int) {
    withSession(conn) { implicit session =>
      val now = (java.time.LocalDate.now().toEpochDay()) * 86400000l 
      val newExpiry = (java.time.LocalDate.now().toEpochDay() + HOLD_LENGTH_DAYS) * 86400000l
      sql"""SELECT id FROM queue WHERE user="" OR expiry < $now LIMIT $n""".
        as1[Int].foreach(id => {
          sql"""UPDATE SET user=$user, expiry=$newExpiry WHERE id=$id""".execute
        })
    }
  }

  def dequeue(user : String, id : Int) = withSession(conn) { implicit session =>
    sql"""UPDATE queue SET user="" WHERE user=$user AND id=$id""".execute
  }

  def extend(user : String, id : Int) = withSession(conn) { implicit session =>
    val newExpiry = (java.time.LocalDate.now().toEpochDay() + HOLD_LENGTH_DAYS) * 86400000l
    sql"""UPDATE queue SET expiry=$newExpiry WHERE user=$user AND id=$id""".execute
  }

  def remove(user : String, id : Int) = withSession(conn) { implicit session =>
    sql"""DELETE FROM queue WHERE user=$user AND id=$id""".execute
  }

}
