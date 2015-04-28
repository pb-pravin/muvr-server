package io.muvr

import java.text.SimpleDateFormat
import java.util.{UUID, Date}

import spray.http._
import spray.httpx.marshalling.{MetaMarshallers, ToResponseMarshaller, ToResponseMarshallingContext}
import spray.httpx.unmarshalling._
import spray.json.{RootJsonFormat, JsValue, JsString, JsonFormat}
import spray.routing.directives.MarshallingDirectives

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scalaz.{-\/, \/, \/-}

/**
 * Common marshallers companion
 */
object CommonMarshallers {

  /**
   * Response option follows the standard ``Option[A]`` style
   */
  private object ResponseOption {
    def apply[A](o: Option[A]): ResponseOption[A] = o match {
      case Some(v) ⇒ ResponseSome(v)
      case None    ⇒ ResponseNone
    }
  }

  /**
   * The option structure
   * @tparam A the A
   */
  sealed trait ResponseOption[+A]
  /** None */
  case object ResponseNone extends ResponseOption[Nothing]
  /** Some */
  case class ResponseSome[A](value: A) extends ResponseOption[A]

}

trait CommonMarshallers extends MarshallingDirectives with MetaMarshallers {
  import CommonMarshallers._

  implicit class DisjunctUnionFuture(f: Future[_]) {

    def mapRight[B]: Future[\/[String, B]] = f.mapTo[\/[String, B]]

    def mapNoneToEmpty[B](implicit ec: ExecutionContext): Future[ResponseOption[B]] = f.mapTo[Option[B]].map(ResponseOption.apply)

  }

  implicit object DateFSOD extends FromStringOptionDeserializer[Date] {
    val format = new SimpleDateFormat("yyyy-MM-dd")

    override def apply(v: Option[String]): Deserialized[Date] = {
      v.map { s ⇒ Try { Right(format.parse(s)) }.getOrElse(Left(MalformedContent("Invalid date " + s))) } getOrElse Left(ContentExpected)
    }
  }

  implicit def duToResponseMarshaller[A : ToResponseMarshaller, B : ToResponseMarshaller]: ToResponseMarshaller[\/[A, B]] = {
    val lm = implicitly[ToResponseMarshaller[A]]
    val rm = implicitly[ToResponseMarshaller[B]]

    new ToResponseMarshaller[\/[A, B]] {
      override def apply(value: \/[A, B], ctx: ToResponseMarshallingContext): Unit = value match {
        case \/-(right) ⇒
          rm.apply(right, ctx)
        case -\/(left)  ⇒
          // TODO: BadRequest?!
          lm.apply(left, ctx.withResponseMapped(_.copy(status = StatusCodes.BadRequest)))
      }
    }
  }

  implicit def responseOptionMarshaller[A : ToResponseMarshaller]: ToResponseMarshaller[ResponseOption[A]] = {
    val m = implicitly[ToResponseMarshaller[A]]

    new ToResponseMarshaller[ResponseOption[A]] {
      override def apply(value: ResponseOption[A], ctx: ToResponseMarshallingContext): Unit = value match {
        case ResponseNone    ⇒ ctx.marshalTo(HttpResponse(StatusCodes.OK, entity = HttpEntity(contentType = ContentTypes.`application/json`, string = "{}")))
        case ResponseSome(a) ⇒ m.apply(a, ctx)
      }
    }
  }

  implicit object UuidJsonFormat extends RootJsonFormat[UUID] {
    def write(x: UUID) = JsString(x toString ())
    def read(value: JsValue) = value match {
      case JsString(x) ⇒ UUID.fromString(x)
    }
  }

  implicit object UnitToResponseMarshaller extends ToResponseMarshaller[Unit] {
    override def apply(value: Unit, ctx: ToResponseMarshallingContext): Unit = ()
  }

  implicit object UserIdFormat extends RootJsonFormat[UserId] {
    override def write(obj: UserId): JsValue = JsString(obj.toString)
    override def read(json: JsValue): UserId = json match {
      case JsString(x) ⇒ UserId(x)
    }
  }

}