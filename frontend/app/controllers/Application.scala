/*
   Copyright (c) 2014 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package controllers

import scala.collection.JavaConversions._
import com.linkedin.data.DataMap
import com.linkedin.restli.common.{ResourceMethod, CollectionMetadata}
import com.linkedin.restli.server.{ResourceLevel, PagingContext}
import com.linkedin.restsearch._
import play.api.mvc._
import com.linkedin.data.schema._
import com.linkedin.restsearch.html._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits._
import org.json.JSONObject
import play.Play
import com.linkedin.restsearch.server.ServiceModelsSchemaResolver
import com.linkedin.restsearch.plugins.SnapshotInitPlugin
import org.apache.commons.lang.StringEscapeUtils
import play.api.mvc.Action
import play.api.libs.json.Json
import com.linkedin.jersey.api.uri.UriBuilder
import java.net.URI
import com.linkedin.restsearch.permlink._
import scala.Some
import java.lang.StringBuilder
import com.linkedin.restsearch.template.utils.Conversions._
import com.linkedin.data.codec.JacksonDataCodec
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import scala.concurrent.Future
import com.linkedin.restli.client
import com.linkedin.restli.client.uribuilders.RestliUriBuilderUtil
import com.linkedin.restli.internal.server.util.DataMapUtils
import com.linkedin.restli.docgen.examplegen.{ExampleRequestResponse, ExampleRequestResponseGenerator}
import scala.Some
import play.api.mvc.SimpleResult

object Application extends Controller with ConsoleUtils {
  val resultsPerPage = 20

  // This regex finds a header name, possibly surrounded by whitespace; followed by the first colon in the line;
  // and then declares that the rest of the line, sans surrounding whitespace, is the body.


  def snapshot = SnapshotInitPlugin.getInstance.snapshotLoader.currentSnapshot

  private val config = Play.application().configuration()
  private val consoleEnabled = config.getBoolean("consoleEnabled")
  private lazy val pastebinClientClass = config.getString("pastebinClientClass", "com.linkedin.restsearch.permlink.GistClient")
  private lazy val pastebinClient = Class.forName(pastebinClientClass).newInstance().asInstanceOf[PastebinClient]
  private lazy val clusterPermlinkClient = new ClusterPermlinkClient(pastebinClient)

  def index = Action { request =>
    Ok(views.html.clusterlist(snapshot.allClusters, snapshot.metadata))
  }

  def dashboard = Action { request =>
    Ok(views.html.dashboard(snapshot.allClusters, snapshot.metadata, snapshot.dashboardStats))
  }

  def uploadPrompt = Action { implicit request =>
    Ok(views.html.upload())
  }

  def upload = Action.async(parse.multipartFormData) { request =>
    request.body.file("snapshot") match {
      case Some(file) => {
        val snapshot = io.Source.fromFile(file.ref.file).mkString
        try {
          val promise = clusterPermlinkClient.write(snapshot)
          promise.map { permlink =>
            Redirect(routes.Application.cluster("permlink:" + permlink))
          }
        } catch {
          case snapshot: SnapshotUploadException => {
            Future(BadRequest(snapshot.getMessage))
          }
          case e: Exception => {
            Future(BadRequest("unknown error parsing file: " + e.getMessage))
          }
        }
      }
      case None => Future(NotFound)
    }
  }

  def errors = Action { request =>
    Ok(views.html.serviceerrors(snapshot.serviceErrors, snapshot.metadata))
  }

  def cluster(clusterName: String) = Action.async { request =>
    loadCluster(clusterName) map { cluster =>
      if (!cluster.isDefined) NotFound
      else Ok(views.html.clusterdetails(cluster.get))
    }
  }

  def service(clusterName: String, serviceKey: String) = Action.async { request =>
    loadCluster(clusterName)  map { cluster =>
      if (!cluster.isDefined) NotFound
      else {
        val serviceOpt = snapshot.findService(cluster.get, serviceKey)
        if (!serviceOpt.isDefined) NotFound
        else {
          val service = serviceOpt.get
          val resolver = cluster.get.getResolver(service)
          val dataSchema = if (service.hasResourceSchema()) {
            Option(resolver.findDataSchema(service.getResourceSchema().getSchema(), new StringBuilder()))
          } else {
            None
          }

          Ok(views.html.servicedetails(cluster.get, service, dataSchema, new TypeRenderer(cluster.get, service, resolver), service.exampleRequestResponseGenerator(resolver)))
        }
      }
    }
  }

  def idl(clusterName: String, serviceKey: String) = Action.async { request =>
    loadCluster(clusterName)  map { cluster =>
      if (!cluster.isDefined) NotFound
      else {
        val serviceOpt = snapshot.findService(cluster.get, serviceKey)
        if (!serviceOpt.isDefined) NotFound
        else {
          val service = serviceOpt.get
          val codec = new JacksonDataCodec()
          codec.setPrettyPrinter(new DefaultPrettyPrinter())
          val json = codec.mapToString(service.getResourceSchema.data())
          Ok(json).as("application/json")
        }
      }
    }
  }

  def model(clusterName: String, serviceKey: String, fqn: String) = Action.async { request =>
    loadCluster(clusterName) map { cluster =>
      if (!cluster.isDefined) NotFound
      else {
        val serviceOpt = snapshot.findService(cluster.get, serviceKey)
        if (!serviceOpt.isDefined) NotFound
        else {
          val service = serviceOpt.get
          val resolver = cluster.get.getResolver(service)
          val dataSchema = if (service.hasResourceSchema()) {
            Option(resolver.findDataSchema(fqn, new StringBuilder()))
          } else {
            None
          }
          if (!dataSchema.isDefined) NotFound
          else {
            Ok(views.html.modeldetails(cluster.get, service, SchemaToJsonEncoder.schemaToJson(dataSchema.get, JsonBuilder.Pretty.INDENTED), dataSchema.get, new TypeRenderer(cluster.get, service, resolver)))
          }
        }
      }
    }
  }

  private def loadCluster(clusterName: String): Future[Option[Cluster]] = {
    if (clusterName.startsWith("permlink:")) {
      clusterPermlinkClient.read(clusterName.substring("permlink:".length)).map(Some(_))
    } else {
      Future(snapshot.findCluster(clusterName))
    }
  }

  private def showConsole(clusterName: String, serviceKey: String, op: String)(implicit requestHeader: RequestHeader): Future[SimpleResult] = {
    if(consoleEnabled == false) Future(Forbidden)
    else {
      val clusterOpt = snapshot.findCluster(clusterName)
      if (!clusterOpt.isDefined) Future(NotFound)
      else {
        val cluster = clusterOpt.get
        val serviceOpt = snapshot.findService(cluster, serviceKey)
        if (!serviceOpt.isDefined) Future(NotFound)
        else {
          val service = serviceOpt.get
          val resolver : DataSchemaResolver = new ServiceModelsSchemaResolver(service)
          val resourceSchema = service.getResourceSchema()
          val exampleGeneratorOpt = service.exampleRequestResponseGenerator(resolver)
          val exampleOpt: Option[ExampleRequestResponse] = exampleGeneratorOpt flatMap { exampleGenerator =>
            val matchingMethods = {
              resourceSchema.methods.find(_.getMethod() == op).map(method => exampleGenerator.method(ResourceMethod.valueOf(method.getMethod.toUpperCase))).toList
            } ++ {
              resourceSchema.finders.find(_.getName() == op).map(finder => exampleGenerator.finder(finder.getName)).toList
            } ++ {
              resourceSchema.actions.find(_.getName() == op).map(action => exampleGenerator.action(action.getName, ResourceLevel.COLLECTION)).toList
            } ++ {
              resourceSchema.entityActions.find(_.getName() == op).map(entityAction => exampleGenerator.action(entityAction.getName, ResourceLevel.ENTITY)).toList
            }
            matchingMethods.headOption
          }

          if(exampleOpt.isDefined) {
            val example = exampleOpt.get
            val request = example.getRequest
            Future(Ok(views.html.console(
              request.method,
              request.uri.toString,
              request.headers.map{ case(k, v) => k + ":" + v }.mkString("\n"),
              request.input,
              None,//Some(method.exampleResponse(typeRenderer).buildHttpResponse())
              snapshot.metadata
            )))
          } else {
            Future(NotFound("Operation not found: " + op))
          }
        }
      }
    }
  }

  private def showPermlink(permlink: String)(implicit request: RequestHeader): Future[SimpleResult] = {
    if(consoleEnabled == false) Future(Forbidden)
    else {
      try {
        pastebinClient.load(permlink) map { pasteCodeStr =>
          val pasteCodeJson = Json.parse(pasteCodeStr)
          val httpMethod = (pasteCodeJson \ "httpMethod").as[String]
          val d2Path = (pasteCodeJson \ "d2Path").as[String]
          val headers = (pasteCodeJson \ "headers").as[String]
          val requestBody = Some((pasteCodeJson \ "body").as[String])
          val responseBody = None
          Ok(views.html.console(httpMethod, d2Path, headers, requestBody, responseBody, snapshot.metadata))
        }
      } catch {
        case e: PastebinException => {
          Future(InternalServerError(e.getMessage))
        }
      }
    }
  }

  def console(clusterName: String, serviceKey: String, op: String, permlink: Option[String]) = Action.async { implicit request =>
    if(consoleEnabled == false) Future(Forbidden)
    else {
      if (permlink.isEmpty)
      {
        showConsole(clusterName, serviceKey, op)
      }
      else
      {
        showPermlink(permlink.get)
      }
    }
  }

  case class ConsoleRequest(httpMethod: String, d2Path: String, headers: String, body: String)

  val userForm = Form(
    mapping(
      "httpMethod" -> text,
      "d2path" -> text,
      "headers" -> text,
      "body" -> text
    )(ConsoleRequest.apply)(ConsoleRequest.unapply)
  )

  def send(clusterName: String, serviceKey: String, op: String) = Action.async { implicit request =>
    loadCluster(clusterName)  flatMap { cluster =>
      if (!cluster.isDefined) Future(NotFound)
      else {
        val serviceOpt = snapshot.findService(cluster.get, serviceKey)
        if (!serviceOpt.isDefined) Future(NotFound)
        else {
          if(consoleEnabled == false) Future(Forbidden)
          else {
            val consoleRequest = userForm.bindFromRequest.get
            val headers = parseHeadersFromConsole(consoleRequest.headers)
            val path = if(consoleRequest.d2Path.trim().startsWith("/")) consoleRequest.d2Path.trim() else "/" + consoleRequest.d2Path.trim()
            val d2request = WS.url(serviceOpt.get.getUrl /*ExternalSites.createD2Url(path)*/).withHeaders(headers: _*)
            if(d2request == null) throw new IllegalArgumentException("Malformed request path: " + path)
            val promise = consoleRequest.httpMethod match {
              case "POST" => d2request.post(consoleRequest.body)
              case "PUT" => d2request.put(consoleRequest.body)
              case "DELETE" => d2request.delete()
              case "GET" => d2request.get()
              case _ => throw new IllegalArgumentException("Malformed httpMethod")
            }

            promise.map { d2response =>
              val headers = d2response.ahcResponse.getHeaders.iterator().toList.map( h => h.getKey() + ": " + h.getValue().mkString(",")).mkString("\n")
              val responseBody = if (d2response.body.startsWith("{")) {
                Some("HTTP/1.1 " + d2response.status + " " + d2response.statusText + "\n" + headers + "\n\n" + prettyPrintJsonResponse(new JSONObject(d2response.body)))
              } else {
                Some("HTTP/1.1 " + d2response.status + " " + d2response.statusText + "\n" + headers + "\n\n" + d2response.body)
              }
              Ok(views.html.console(consoleRequest.httpMethod, consoleRequest.d2Path, consoleRequest.headers, Some(consoleRequest.body), responseBody, snapshot.metadata))
            }
          }
        }
      }
    }
  }

  def newPermlink = Action.async { implicit request =>
    val consoleRequest = userForm.bindFromRequest.get
    val pasteCode = JsonUtil.pojoAsJsonString(consoleRequest)
    pastebinClient.store(pasteCode)
    try {
      pastebinClient.store(pasteCode).map { pasteId =>
        val origin = request.body.asFormUrlEncoded.get.get("origin").get.head
        val permlinkUri = UriBuilder.fromUri(new URI(origin)).queryParam("permlink", pasteId).build()
        Ok(permlinkUri.toString)
      }
    } catch {
      case e: PastebinException => {
        Future(InternalServerError(e.getMessage))
      }
    }
  }

  def prettyPrintJsonResponse(jsonResponse: JSONObject) = {

    val prettyJson = if(jsonResponse.has("stackTrace")) {
      val stackTrace = jsonResponse.getString("stackTrace").replaceAll("\\n", "\n").replaceAll("\\t", "\t")
      jsonResponse.put("stackTrace", "{see below}")
      jsonResponse.toString(2) + "\n\n" + stackTrace
    } else {
      jsonResponse.toString(2)
    }

    // add deco links to all URNs in response body
    val escaped = StringEscapeUtils.escapeHtml(prettyJson)
    /*val escaped = "(\"|&quot;)(urn:li:.*)(\"|&quot;)".r.replaceAllIn(escaped, m => {
        val urn = m.group(2)
        "<a href=\"" + ExternalSites.createDecoUrl(urn) + "\">" + urn + "</a>"
      }
    )*/
    escaped
  }

  def searchResources = Action { request =>
    val keyword = request.queryString.get("keyword").getOrElse(List("")).get(0)

    if (keyword == "") {
      Ok(views.html.clusterlist(snapshot.allClusters, snapshot.metadata))
    } else {
      val page = request.queryString.get("page").getOrElse(Seq("0")).get(0).toInt
      val protocol = request.queryString.get("protocol") map { _.get(0) }
      val (services, paging, _) = searchServices(Some(keyword), page, protocol)
      Ok(views.html.searchresults(keyword, protocol.getOrElse("REST"), services, paging, snapshot.metadata))
    }
  }

  private def searchServices(keyword: Option[String], page: Int, protocol: Option[String]): (List[Service], CollectionMetadata, DataMap) = {
    val paging = new PagingContext(page*resultsPerPage, resultsPerPage)
    val response = snapshot.search(paging, keyword.getOrElse(""))
    val collectionMetadata = new CollectionMetadata()
    collectionMetadata.setStart(page*resultsPerPage)
    collectionMetadata.setCount(resultsPerPage)
    collectionMetadata.setTotal(response.getTotal())
    (response.getElements().toList, collectionMetadata, response.getMetadata().data())
  }

  /**
   * Recursive, so did not put it in a view.  TODO: can this be made a method in a view?
   */
  def subresources(cluster: Cluster, service: Service): String = {
    val subs = service.allSubresourcesAsServices
    if(subs.size > 0) {
      "<ul>" + {
        subs.map { sub =>
          "<li class=\"list-unstyled\">" +
          "<a " + {
              if(sub.hasResourceSchema && sub.getResourceSchema.isScoped) {
                "class=\"text-muted\""
              } else ""
            } + " href=\"" + routes.Application.service(cluster.getName(), sub.keysToResource.mkString(".")) +"\">/" + sub.getKey() +
            {
              if(sub.hasResourceSchema && sub.getResourceSchema.isScoped) {
                " " + sub.getResourceSchema.scope
              } else ""
            } + subresources(cluster, sub) +
            "</a>" +
          "</li>"
        }.mkString("")
      } + "</ul>"
    } else ""
  }
}