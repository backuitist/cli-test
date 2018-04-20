package org.backuity.clist.util

import org.backuity.clist.ParsingException

import scala.util.control.NonFatal

trait Read[A] {
  def reads(string: String): A
}

case class ReadException(value: String, expected: String) extends RuntimeException(s"value: $value, expected: $expected ")

/**
 * Note that we do not support scala Enumeration as this would require having a runtime dependency
 * on the reflection API. On the other hand Java enums are supported.
 */
object Read {
  import java.io.File
  import java.net.URI
  import java.text.SimpleDateFormat
  import java.util.{Calendar, GregorianCalendar, Locale}

  def reads[A](expecting: String)(f: String => A): Read[A] = new Read[A] {
    def reads(string: String): A = {
      try {
        f(string)
      } catch {
        case NonFatal(e) if !e.isInstanceOf[ReadException] =>
          throw new ReadException(string, expected = expecting)
      }
    }
  }

  implicit val intRead: Read[Int] = reads("an Int") { _.toInt }
  implicit val stringRead: Read[String] = reads(""){ identity }
  implicit val doubleRead: Read[Double] = reads("a Double") { _.toDouble }
  implicit val booleanRead: Read[Boolean] =
    reads("a boolean, such as 'true', 'yes', '1' or 'false', 'no', '0'") { _.toLowerCase match {
      case "true" | "yes" | "1" => true
      case "false" | "no" | "0"      => false
      case s =>
        throw new IllegalArgumentException("'" + s + "' is not a boolean.")
    }}
  implicit val longRead: Read[Long] = reads("a Long") { _.toLong }
  implicit val bigIntRead: Read[BigInt] = reads("a BigInt") { BigInt(_) }
  implicit val bigDecimalRead: Read[BigDecimal] = reads("a BigDecimal") { BigDecimal(_) }

  implicit def optionRead[T : Manifest : Read] : Read[Option[T]] = reads("") { s =>
    Some(implicitly[Read[T]].reads(s))
  }

  implicit def javaEnumRead[T <: Enum[T] : Manifest]: Read[T] = reads("") { s =>
    val values = manifest[T].runtimeClass.getEnumConstants.map(_.toString)
    if( !values.contains(s.toUpperCase)) {
      throw ReadException(value = s, expected = "one of " + values.sorted.mkString(",").toLowerCase)
    } else {
      Enum.valueOf(manifest[T].runtimeClass.asInstanceOf[Class[T]], s.toUpperCase)
    }
  }

  implicit def seqRead[T](implicit readT: Read[T]): Read[Seq[T]] = reads("") { str =>
    str.split("\\p{Blank}+").map { readT.reads }
  }

  implicit val yyyymmdddRead: Read[Calendar] = calendarRead("yyyy-MM-dd")

  def calendarRead(pattern: String): Read[Calendar] = calendarRead(pattern, Locale.getDefault)
  def calendarRead(pattern: String, locale: Locale): Read[Calendar] =
    reads(s"a date formatted as $pattern") { s =>
      val fmt = new SimpleDateFormat(pattern)
      val c = new GregorianCalendar
      c.setTime(fmt.parse(s))
      c
    }

  implicit val fileRead: Read[File] = reads("a File") { new File(_) }

  implicit val uriRead: Read[URI] = reads("a URI") { new URI(_) }

  implicit def tupleRead[A1: Read, A2: Read]: Read[(A1, A2)] = new Read[(A1, A2)] {
    def reads(s: String) = {
      splitKeyValue(s) match {
        case (k, v) => implicitly[Read[A1]].reads(k) -> implicitly[Read[A2]].reads(v)
      }
    }
  }

  private def splitKeyValue(s: String): (String, String) =
    s.indexOf('=') match {
      case -1 => throw new IllegalArgumentException("Expected a key=value pair")
      case n: Int => (s.slice(0, n), s.slice(n + 1, s.length))
    }

  implicit val unitRead: Read[Unit] = new Read[Unit] {
    def reads(s: String): Unit = ()
  }
}


