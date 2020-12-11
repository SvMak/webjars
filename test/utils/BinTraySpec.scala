package utils

import akka.util.Timeout
import org.apache.commons.io.IOUtils
import play.api.libs.json.JsArray
import play.api.libs.ws.WSClient
import play.api.test._
import play.api.{Configuration, Environment}

import java.net.{URI, URL}
import java.util.Date
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

class BinTraySpec extends PlaySpecification with GlobalApplication {

  override implicit def defaultAwaitTimeout: Timeout = 60.seconds

  lazy val environment: Environment = application.injector.instanceOf[Environment]
  lazy val ws: WSClient = application.injector.instanceOf[WSClient]
  lazy val config: Configuration = application.injector.instanceOf[Configuration]
  lazy val mavenCentral: MavenCentral = application.injector.instanceOf[MavenCentral]
  lazy val ec: ExecutionContext = application.injector.instanceOf[ExecutionContext]
  lazy val binTray: BinTray = new BinTrayLive(ws, config, mavenCentral)(ec)

  "BinTray with auth" should {
    if (application.configuration.getOptional[String]("bintray.username").isEmpty)
      "BinTray Auth" in skipped("skipped due to missing config")
    else {
      val repo = Random.alphanumeric.take(8).mkString

      "get packages" in {
        await(binTray.getPackages("webjars", "test")) must beAnInstanceOf[JsArray]
      }
      "create a package with an invalid gitHubRepo should fail" in {
        await(binTray.createPackage("webjars", "test", repo, "foo description", Seq("test"), Set("MIT"), new URI("http://github.com/webjars/webjars.git"), None, None, Some("asdfqwer1236sdfgasdf/zxcvasdfqwer123"))) must throwA[Exception]("No repository found under this GitHub path")
      }
      "create a package" in {
        val result = await(binTray.createPackage("webjars", "test", repo, "foo description", Seq("test"), Set("MIT"), new URI("http://github.com/webjars/webjars.git"), Some(new URL("http://webjars.org")), Some(new URL("http://github.com/webjars/webjars/issues")), Some("webjars/webjars")))
        (result \ "created").asOpt[Date] must beSome
      }
      "get or create package should work" in {
        val result = await(binTray.getOrCreatePackage("webjars", "test", repo, "foo description", Seq("test"), Set("MIT"), new URI("http://github.com/webjars/webjars"), Some(new URL("http://webjars.org")), Some(new URL("http://github.com/webjars/webjars/issues")), Some("webjars/webjars")))
        (result \ "created").asOpt[Date] must beSome
      }
      "create a version" in {
        val result = await(binTray.createVersion("webjars", "test", repo, "0.0.1", "Release 0.0.1"))
        (result \ "created").asOpt[Date] must beSome
      }
      "fail to create an existing version" in {
        await(binTray.createVersion("webjars", "test", repo, "0.0.1", "Release 0.0.1")) must throwA[BinTray.VersionExists]
      }
      "overwrite an existing version" in {
        val result = await(binTray.createOrOverwriteVersion("webjars", "test", repo, "0.0.1", "Release 0.0.1"))
        (result \ "created").asOpt[Date] must beSome
      }
      "upload a maven artifact" in {
        val bytes = environment.resourceAsStream("foo.jar").map { inputStream =>
          val fileBytes = IOUtils.toByteArray(inputStream)
          inputStream.close()
          fileBytes
        }.get
        val result = await(binTray.uploadMavenArtifact("webjars", "test", repo, "org/webjars/bower/" + repo + "/0.0.1/" + repo + "-0.0.1.jar", bytes))
        (result \ "message").asOpt[String] must beSome("success")
      }
      "sign an artifact" in {
        val result = await(binTray.signVersion("webjars", "test", repo, "0.0.1"))
        (result \ "message").asOpt[String] must beSome("success")
      }
      "publish an artifact" in {
        val result = await(binTray.publishVersion("webjars", "test", repo, "0.0.1"))
        (result \ "files").asOpt[Int] must beSome(2)
      }

      step {
        await(binTray.deletePackage("webjars", "test", repo))
      }
    }
  }

}
