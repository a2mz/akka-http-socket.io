import SessionRegistryActor.{AskForSID, Disconnect, UpdateOut}
import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, _}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.{ByteString, Timeout}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class SocketIoService(actorProps: Props, prefix: String = "socket.io")(
  implicit system: ActorSystem
) {
  val pingTimeout = 6000
  //60000
  val pingInterval = 2500
  //25000
  implicit val timeout = Timeout(5.seconds)
  //TODO UPDATE TO SOME OTHER ACTOR!!!
  private val socketActorRegistry =
    system.actorOf(SessionRegistryActor.props(EchoActor.props))

  import system.dispatcher

  def transport(sid: String) = {
    setCookie(HttpCookie("io", value = sid, path = Some("/"), httpOnly = true)) {
      //            val message = openMessage(result._1)
      val message =
        s"""0{"sid":"$sid","upgrades":["websocket"],"pingInterval":$pingInterval,"pingTimeout":$pingTimeout}"""

      val sourceFactory: Source[ChunkStreamPart, NotUsed] =
        Source(
          List(
            HttpEntity
              .Chunk(ByteString(0)), //<0 for string data, 1 for binary data>
            HttpEntity.Chunk(ByteString(message.length + 5)), //>Lenght - Find Way how to more than possible
            HttpEntity.Chunk(ByteString(255)), //<The number 255>
            HttpEntity
              .Chunk(ByteString(2)), //<Any number of numbers between 0 and 9>
            HttpEntity.Chunk(ByteString(1)), //TODO find why i need it
            HttpEntity.ChunkStreamPart(ByteString(message)),
            HttpEntity.LastChunk
          )
        )

      complete(
        HttpResponse(
          entity = HttpEntity
            .Chunked(ContentTypes.`application/octet-stream`, sourceFactory)
        )
      )
    }
  }

  def route: Route = pathPrefix(prefix) {
    (get & path("socket.io.js")) {
      getFromResource("socket.io-client-1.7.1/dist/socket.io.js")
    } ~
      (get & parameter('transport, "EIO".as[Int], 'sid.?)) {
        case ("polling", eio, None) =>
          onComplete(
            ask(socketActorRegistry, AskForSID(eio))
              .mapTo[String]
              .map(transport)
          ) {
            case Success(value) => value
            case _ => reject
          }

        case ("polling", eio, Some(sid)) =>
          val test = Source
            .actorRef[HttpEntity.ChunkStreamPart](10, OverflowStrategy.fail)
            .mapMaterializedValue { outActor =>
//                       socketActorRegistry ! UpdateOut(sid, outActor)
              outActor ! HttpEntity.Chunk(ByteString(0))
              outActor ! HttpEntity
                .Chunk(ByteString(0)) //<0 for string data, 1 for binary data>
              outActor ! HttpEntity
                .Chunk(ByteString(97)) //>Lenght - Find Way how to more than possible
              outActor ! HttpEntity.Chunk(ByteString(255)) //<The number 255>
              outActor ! HttpEntity
                .Chunk(ByteString(2)) //<Any number of numbers between 0 and 9>
              outActor ! HttpEntity.ChunkStreamPart(ByteString(s"""40"""))
              outActor ! HttpEntity.LastChunk
              NotUsed
            }
          complete(
            HttpResponse(
              entity = HttpEntity
                .Chunked(ContentTypes.`application/octet-stream`, test)
            )
          )

        case ("websocket", eio, sessionId) =>
          onComplete(
            ask(socketActorRegistry, AskForSID(eio, sessionId)).mapTo[String]
          ) {
            case Success(sid) =>
              handleWebSocketMessages(newConnection(eio, sessionId, sid))
            case Failure(err) => complete(err.toString)
          }

        case _ => reject
      }
  }

  private def newConnection(eio: Int, sessionId: Option[String], sid: String): Flow[Message, Message, NotUsed] = {

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].map {
        // transform websocket message to domain message
        case TextMessage.Strict(text) =>
          SessionRegistryActor.IncomingMessage(sid, text)
      }.to(
        Sink.actorRef[SessionRegistryActor.IncomingMessage](
          socketActorRegistry,
          Disconnect(sid)
        )
      )

    val outgoingMessages: Source[Message, NotUsed] =
      Source
        .actorRef[SessionRegistryActor.OutgoingMessage](
          10,
          OverflowStrategy.fail
        )
        .mapMaterializedValue { outActor =>
          socketActorRegistry ! UpdateOut(sid, outActor)
          sessionId match {
            case Some(_) =>
              println("JUST UPGRADE NO NEED TO SEND SOMETHING")
            case None => //TODO move to SessionRegistryActor
              outActor ! SessionRegistryActor.OutgoingMessage(
                s"""0{"sid":"$sid","upgrades":["websocket"],"pingInterval":$pingInterval,"pingTimeout":$pingTimeout}"""
              )
              outActor ! SessionRegistryActor.OutgoingMessage(s"""40""")
          }
          NotUsed
        }
        .map(
          // transform domain message to web socket message
          (outMsg: SessionRegistryActor.OutgoingMessage) => TextMessage(outMsg.text)
        )
    // then combine both to a flow
    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }
}
