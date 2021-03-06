/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.SocketException

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.testkit.{ AkkaSpec, EventFilter, SocketUtil }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.util.Try

class HttpAppSpec extends AkkaSpec with RequestBuilding with Eventually {
  import system.dispatcher

  class MinimalApp extends HttpApp {

    val shutdownPromise = Promise[Done]()
    val bindingPromise = Promise[Done]()

    def shutdownServer(): Unit = shutdownPromise.success(Done)

    override protected def routes: Route =
      path("foo") {
        complete("bar")
      }

    override protected def postHttpBinding(binding: ServerBinding): Unit = {
      super.postHttpBinding(binding)
      bindingPromise.success(Done)
    }

    override protected def waitForShutdownSignal(system: ActorSystem)(implicit ec: ExecutionContext): Future[Done] = {
      shutdownPromise.future
    }
  }

  class SneakyServer extends MinimalApp {

    val postBindingCalled = new AtomicBoolean(false)
    val postBindingFailureCalled = new AtomicBoolean(false)
    val postShutdownCalled = new AtomicBoolean(false)

    override protected def postHttpBindingFailure(cause: Throwable): Unit = postBindingFailureCalled.set(true)

    override protected def postHttpBinding(binding: ServerBinding): Unit = {
      postBindingCalled.set(true)
      bindingPromise.success(Done)
    }

    override protected def postServerShutdown(attempt: Try[Done], system: ActorSystem): Unit = postShutdownCalled.set(true)
  }

  def withMinimal(testCode: (MinimalApp, String, Int) ⇒ Any): Unit = {
    val (host, port) = SocketUtil.temporaryServerHostnameAndPort()
    val minimal = new MinimalApp()
    try testCode(minimal, host, port)
    finally {
      if (!minimal.shutdownPromise.isCompleted) minimal.shutdownPromise.success(Done)
    }
  }

  def withSneaky(testCode: (SneakyServer, String, Int) ⇒ Any): Unit = {
    val (host, port) = SocketUtil.temporaryServerHostnameAndPort()
    val sneaky = new SneakyServer()
    try testCode(sneaky, host, port)
    finally {
      if (!sneaky.shutdownPromise.isCompleted) sneaky.shutdownPromise.success(Done)
    }
  }

  "HttpApp" should {

    "start only with host and port" in withMinimal { (minimal, host, port) ⇒
      val server = Future {
        minimal.startServer(host, port)
      }

      Await.result(minimal.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

      // Checking server is up and running
      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      minimal.shutdownServer()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
    }

    "start without ActorSystem" in withMinimal { (minimal, host, port) ⇒

      val server = Future {
        minimal.startServer(host, port, ServerSettings(ConfigFactory.load))
      }

      Await.result(minimal.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

      // Checking server is up and running
      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      minimal.shutdownServer()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "start providing an ActorSystem" in withMinimal { (minimal, host, port) ⇒

      val server = Future {
        minimal.startServer(host, port, system)
      }

      Await.result(minimal.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

      // Checking server is up and running
      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      minimal.shutdownServer()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
      system.whenTerminated.isCompleted should ===(false)

    }

    "start providing an ActorSystem and Settings" in withMinimal { (minimal, host, port) ⇒

      val server = Future {
        minimal.startServer(host, port, ServerSettings(system), system)
      }

      Await.result(minimal.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

      // Checking server is up and running
      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      minimal.shutdownServer()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
      system.whenTerminated.isCompleted should ===(false)

    }

    "provide binding if available" in withMinimal { (minimal, host, port) ⇒

      minimal.binding().isFailure should ===(true)

      val server = Future {
        minimal.startServer(host, port, ServerSettings(ConfigFactory.load))
      }

      Await.result(minimal.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

      minimal.binding().isSuccess should ===(true)
      minimal.binding().get.localAddress.getPort should ===(port)
      minimal.binding().get.localAddress.getAddress.getHostAddress should ===(host)

      // Checking server is up and running
      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      minimal.shutdownServer()
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "notify" when {

      "shutting down" in withSneaky { (sneaky, host, port) ⇒

        val server = Future {
          sneaky.startServer(host, port, ServerSettings(ConfigFactory.load))
        }

        Await.result(sneaky.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

        sneaky.postShutdownCalled.get() should ===(false)

        // Checking server is up and running
        callAndVerify(host, port, "foo")

        // Requesting the server to shutdown
        sneaky.shutdownServer()
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)
        eventually {
          sneaky.postShutdownCalled.get() should ===(true)
        }

      }

      "after binding is successful" in withSneaky { (sneaky, host, port) ⇒

        val server = Future {
          sneaky.startServer(host, port, ServerSettings(ConfigFactory.load))
        }

        val binding = Await.result(sneaky.bindingPromise.future, Duration(5, TimeUnit.SECONDS))

        sneaky.postBindingCalled.get() should ===(true)

        // Checking server is up and running
        callAndVerify(host, port, "foo")

        // Requesting the server to shutdown
        sneaky.shutdownServer()
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)

      }

      "after binding is unsuccessful" in withSneaky { (sneaky, host, _) ⇒
        EventFilter[SocketException](message = "Permission denied", occurrences = 1) intercept {
          sneaky.startServer(host, 1, system)
        }

        eventually {
          sneaky.postBindingFailureCalled.get() should ===(true)
        }

      }

    }

  }

  private def callAndVerify(host: String, port: Int, path: String) = {

    implicit val mat = ActorMaterializer()

    val request = HttpRequest(uri = s"http://$host:$port/$path")
    val response = Http().singleRequest(request)
    response.futureValue.status should ===(StatusCodes.OK)
  }
}
