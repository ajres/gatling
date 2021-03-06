/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.core.structure

import scala.concurrent.duration.Duration

import com.typesafe.scalalogging.StrictLogging

import io.gatling.core.action.UserEnd
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.config.{ GatlingConfiguration, Protocol, Protocols }
import io.gatling.core.controller.inject.{ InjectionProfile, InjectionStep }
import io.gatling.core.controller.throttle.{ ThrottlingProfile, Throttling }
import io.gatling.core.pause._
import io.gatling.core.scenario.Scenario
import io.gatling.core.session.Expression

/**
 * The scenario builder is used in the DSL to define the scenario
 *
 * @param name the name of the scenario
 * @param actionBuilders the list of all the actions that compose the scenario
 */
case class ScenarioBuilder(name: String, actionBuilders: List[ActionBuilder] = Nil) extends StructureBuilder[ScenarioBuilder] {

  private[core] def newInstance(actionBuilders: List[ActionBuilder]) = copy(actionBuilders = actionBuilders)

  def inject(iss: InjectionStep*): PopulatedScenarioBuilder = inject(iss.toIterable)

  def inject(iss: Iterable[InjectionStep]): PopulatedScenarioBuilder = {
    require(iss.nonEmpty, "Calling inject with empty injection steps")

    val defaultProtocols = actionBuilders.foldLeft(Protocols()) { (protocols, actionBuilder) =>
      actionBuilder.registerDefaultProtocols(protocols)
    }

    new PopulatedScenarioBuilder(this, InjectionProfile(iss), defaultProtocols)
  }
}

case class PopulatedScenarioBuilder(
  scenarioBuilder: ScenarioBuilder,
  injectionProfile: InjectionProfile,
  defaultProtocols: Protocols,
  scenarioProtocols: List[Protocol] = Nil,
  scenarioThrottling: Option[ThrottlingProfile] = None,
  pauseType: Option[PauseType] = None)
    extends StrictLogging {

  def protocols(protocols: Protocol*) = copy(scenarioProtocols = this.scenarioProtocols ++ protocols)

  def disablePauses = pauses(Disabled)
  def constantPauses = pauses(Constant)
  def exponentialPauses = pauses(Exponential)
  def customPauses(custom: Expression[Long]) = pauses(Custom(custom))
  def uniformPauses(plusOrMinus: Double) = pauses(UniformPercentage(plusOrMinus))
  def uniformPauses(plusOrMinus: Duration) = pauses(UniformDuration(plusOrMinus))
  def pauses(pauseType: PauseType) = copy(pauseType = Some(pauseType))

  def throttle(throttlingBuilders: Throttling*) = {
    require(throttlingBuilders.nonEmpty, s"Scenario '${scenarioBuilder.name}' has an empty throttling definition.")
    val steps = throttlingBuilders.toList.map(_.steps).reverse.flatten
    copy(scenarioThrottling = Some(Throttling(steps).profile))
  }

  /**
   * @param globalProtocols the protocols
   * @param globalPauseType the pause type
   * @param globalThrottling the optional throttling profile
   * @return the scenario
   */
  private[core] def build(globalProtocols: List[Protocol], globalPauseType: PauseType, globalThrottling: Option[ThrottlingProfile])(implicit configuration: GatlingConfiguration): Scenario = {

    val resolvedPauseType = globalThrottling.orElse(scenarioThrottling).map { _ =>
      logger.info("Throttle is enabled, disabling pauses")
      Disabled
    }.orElse(pauseType).getOrElse(globalPauseType)

    val protocolsMap = (defaultProtocols ++ globalProtocols ++ scenarioProtocols).protocols

    val protocols = new Protocols(protocolsMap, resolvedPauseType, globalThrottling, scenarioThrottling)

    protocols.warmUp

    val entryPoint = scenarioBuilder.build(UserEnd.instance, protocols)
    new Scenario(scenarioBuilder.name, entryPoint, injectionProfile, protocols)
  }
}
