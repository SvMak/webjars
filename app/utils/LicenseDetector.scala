package utils

import java.net.URL

import javax.inject.Inject
import play.api.Logging
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}

class LicenseDetector @Inject() (ws: WSClient) (implicit ec: ExecutionContext) extends Logging {

  def gitHubLicenseDetect(maybeGitHubOrgRepo: Option[String]): Future[String] = {
    def fetchLicense(url: String): Future[String] = ws.url(url).get().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.body)
        case _ => Future.failed(NoValidLicenses())
      }
    }

    maybeGitHubOrgRepo.map { gitHubOrgRepo =>
      // look on master for a license
      fetchLicense(s"https://github-license-service.herokuapp.com/$gitHubOrgRepo").recoverWith {
        case _: Exception =>
          // look on gh-pages
          fetchLicense(s"https://github-license-service.herokuapp.com/$gitHubOrgRepo/gh-pages")
     }
    } getOrElse Future.failed(NoValidLicenses())
  }

  def licenseDetect(contents: String): Future[String] = {
    ws.url("https://oss-license-detector.herokuapp.com/").post(contents).flatMap { licenseResponse =>
      licenseResponse.status match {
        case Status.OK =>
          Future.successful(licenseResponse.body)
        case _ =>
          logger.error("License fetch error:\n" + contents + "\n" + licenseResponse.body)
          Future.failed(new Exception(licenseResponse.body))
      }
    }
  }

  def licenseDetect(url: URL): Future[String] = {
    ws.url(url.toString).get().flatMap { response =>
      response.status match {
        case Status.OK if response.header(HeaderNames.CONTENT_TYPE).exists(_.startsWith(MimeTypes.TEXT)) =>
          licenseDetect(response.body)
        case Status.OK =>
          Future.failed(new Exception(s"License at $url was not plain text"))
        case _ =>
          Future.failed(new Exception(s"Could not fetch license at $url - response was: ${response.body}"))
      }
    }
  }

  val typicalLicenseFiles = Set("LICENSE", "LICENSE.txt", "license.md")

  def normalize(s: String): String = s.replace(" ", "").replace("-", "").toLowerCase

  val licenseSynonyms: Map[String, String] = Map(
    "OFL-1.1" -> "Openfont-1.1",
    "Artistic-2.0" -> "Artistic-License-2.0",
    "Apache 2" -> "Apache-2.0",
    "Apache 2.0" -> "Apache-2.0",
    "Apache License, v2.0" -> "Apache-2.0",
    "Apache License, Version 2.0" -> "Apache-2.0",
    "Apache License 2.0" -> "Apache-2.0",
    "BSD-2-Clause" -> "BSD 2-Clause",
    "BSD-3-Clause" -> "BSD 3-Clause",
    "BSD-3" -> "BSD 3-Clause",
    "New BSD License" -> "BSD 3-Clause",
    "GPLv2" -> "GPL-2.0",
    "GPLv3" -> "GPL-3.0",
    "MIT/X11" -> "MIT",
    "AGPL-3.0" -> "AGPL-V3",
    "PSF" -> "PythonSoftFoundation",
    "W3C-20150513" -> "W3C",
  ) map {
    case (key, value) => normalize(key) -> value
  }

  //val normalizedCommonToSpdxLicenses = licenseSynonyms.map { case (from, to) => normalize(from) -> to }

  // from: https://bintray.com/docs/api/
  val availableLicenses = Set("AFL-2.1", "AFL-3.0", "AGPL-V3", "Apache-1.0", "Apache-1.1", "Apache-2.0", "APL-1.0", "APSL-2.0", "Artistic-License-2.0", "Attribution", "Bouncy-Castle", "BSD", "BSD 2-Clause", "BSD 3-Clause", "BSL-1.0", "CA-TOSL-1.1", "CC0-1.0", "CDDL-1.0", "Codehaus", "CPAL-1.0", "CPL-1.0", "CPOL-1.02", "CUAOFFICE-1.0", "Day", "Day-Addendum", "ECL2", "Eiffel-2.0", "Entessa-1.0", "EPL-1.0", "EUDATAGRID", "EUPL-1.1", "Fair", "Frameworx-1.0", "Go", "GPL-2.0", "GPL-2.0+CE", "GPL-3.0", "Historical", "HSQLDB", "IBMPL-1.0", "IPAFont-1.0", "ISC", "IU-Extreme-1.1.1", "JA-SIG", "JSON", "JTidy", "LGPL-2.0", "LGPL-2.1", "LGPL-3.0", "Lucent-1.02", "MirOS", "MIT", "Motosoto-0.9.1", "Mozilla-1.1", "MPL-2.0", "MS-PL", "MS-RL", "Multics", "NASA-1.3", "NAUMEN", "Nethack", "Nokia-1.0a", "NOSL-3.0", "NTP", "NUnit-2.6.3", "NUnit-Test-Adapter-2.6.3", "OCLC-2.0", "Openfont-1.1", "Opengroup", "OpenSSL", "OSL-3.0", "PHP-3.0", "PostgreSQL", "Public Domain", "Public Domain - SUN", "PythonPL", "PythonSoftFoundation", "QTPL-1.0", "Real-1.0", "RicohPL", "RPL-1.5", "Scala", "SimPL-2.0", "Sleepycat", "SUNPublic-1.0", "Sybase-1.0", "TMate", "Unicode-DFS-2015", "Unlicense", "UoI-NCSA", "VovidaPL-1.0", "W3C", "WTFPL", "wxWindows", "Xnet", "ZLIB", "ZPL-2.0")

  //val normalizedAvailableLicenses = availableLicenses.map(license => normalize(license) -> license).toMap

  def validLicenses(licenses: Seq[String]): Seq[String] = {
    val licensesWithSynonyms = licenses.map { license =>
      // deal with synonyms and normalization
      licenseSynonyms.getOrElse(normalize(license), license)
    }

    availableLicenses.filter { availableLicense =>
      licensesWithSynonyms.exists { license =>
        license.equalsIgnoreCase(availableLicense)
      }
    }.toSeq
  }

}

case class LicenseNotFoundException(message: String, cause: Exception = null) extends Exception(message, cause)
case class NoValidLicenses() extends Exception("no valid licenses found")

object LicenseDetector {
  def defaultUrls(licenses: Iterable[String]): Map[String, String] = {
    licenses.map { name =>
      name -> s"https://spdx.org/licenses/$name#licenseText"
    }.toMap
  }
}
