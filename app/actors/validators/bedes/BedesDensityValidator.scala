/*
 * Copyright 2017 Maalka
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package actors.validators.bedes

import java.util.UUID

import actors.ValidatorActors.BedesValidators.{BedesValidator, BedesValidatorCompanion}
import actors.validators.Validator
import actors.validators.Validator.MapValid
import actors.validators.basic.{Exists, Numeric, WithinRange}
import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.maalka.bedes.BEDESTransformResult
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future

case class BedesDensityValidatorProps(arguments: Option[JsObject] = None) extends BedesValidatorCompanion {

  def props(guid: String,
            name: String,
            propertyId: String,
            validatorCategory: Option[String],
            _arguments: Option[JsObject])(implicit actorSystem: ActorSystem): Props =
    Props(BedesDensityValidator(guid, name, propertyId, validatorCategory, arguments))
}

/**
  * @param guid - guid
  * @param name - validator name
  * @param propertyId - to document
  * @param validatorCategory - to document
  * @param arguments - should include argument 'expectedValue' that will be used for comparision
  */
case class BedesDensityValidator(guid: String,
                                 name: String,
                                 propertyId: String,
                                 validatorCategory: Option[String],
                                 override val arguments: Option[JsObject] = None)(implicit actorSystem: ActorSystem) extends BedesValidator {

  // the materializer to use.  this must be an ActorMaterializer
  private val bedesGFACompositeField = play.Play.application.configuration.getString("maalka.bedesGFACompositeField")

  implicit val materializer = ActorMaterializer()

  val validator = "bedes_range_validator"
  override val bedesCompositeName: String = arguments.flatMap { arg =>
    (arg \ "compositeName").asOpt[String]
  }.get

  val min: Option[Double] = arguments.flatMap { arg =>
    (arg \ "min").asOpt[Double] orElse (arg \ "min").asOpt[String].map(_.toDouble)
  }
  val max: Option[Double] = arguments.flatMap { arg =>
    (arg \ "max").asOpt[Double] orElse (arg \ "max").asOpt[String].map(_.toDouble)
  }

  val componentValidators = Seq(
    propsWrapper(Numeric.props),
    propsWrapper(Exists.props),
    propsWrapper(WithinRange.props, Option(Json.obj("min" -> min, "max" -> max)))
  )


  def isValid(refId: UUID, value: Option[Seq[BEDESTransformResult]]): Future[Validator.MapValid] = {
    value.map { tr =>
      tr.find(_.getCompositeName.contains(bedesGFACompositeField)).flatMap(_.getDataValue) ->
        tr.find(_.getCompositeName.contains(bedesCompositeName))
    }.filter(_._1.isDefined).flatMap {
      case (Some(gfa: Double), tr) =>
        log.debug("Found GFA and CompositeName: {}", bedesCompositeName)
        tr.map { t =>
          var value = t.getDataValue.map {
            case v: Int =>
              v / gfa * 1000.0
            case v: Float =>
              v / gfa * 1000.0
            case v: Long =>
              v / gfa * 1000.0
            case v: Double =>
            case None => None
            case i =>
              log.warning("Invalid GFA type: {}", i)
              None
          }
          log.debug("Setting Value to: {}", value)
          (gfa,
            t.setDataValue(value).setBedesType("Double")
          )


        }
      case i =>
        log.debug("Could Not find GFA and CompositeName: {} - {}", bedesCompositeName, i)
        None
    } match {
      case Some((gfa, tr)) =>
        sourceValidateFromComponents(Option(Seq(tr))).map {
          case results if !results.head.valid =>
            MapValid(valid = false, Option("%s is not a number".format(bedesCompositeName)))
          case results if results.lift(1).exists(!_.valid) =>
            MapValid(valid = false, Option("%s is missing".format(bedesCompositeName)))
          case results if results.lift(2).exists(!_.valid) =>
            val formatter = java.text.NumberFormat.getIntegerInstance
            val calculatedMin = min.map { a =>
              (math rint (a * gfa / 1000)  * 100) / 100
            }
            val calculatedMax = max.map{ a =>
              (math rint (a * gfa / 1000)  * 100) / 100
            }
            formatMapValidRangeResponse(bedesCompositeName, calculatedMin, calculatedMax)
          case results =>
            MapValid(valid = true, None)
        }.runWith(Sink.head)
      case None =>
        Future.successful(MapValid(valid = false, Option("%s or %s is missing".format(bedesCompositeName, bedesGFACompositeField))))
    }
  }
}