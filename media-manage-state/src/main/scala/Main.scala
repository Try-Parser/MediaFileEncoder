package media


import akka.actor.typed.ActorSystem
import akka.cluster.typed.Cluster
import com.typesafe.config.ConfigFactory
import media.state.cassandra.CassandraDB

import scala.jdk.CollectionConverters.ListHasAsScala

object ServerState {
	def main(args: Array[String]): Unit = {
		args.headOption match {
			case Some(port) => startNode(Option(port))
			case _ => startNode(None) 
		}
	}
	
	private def startNode(port: Option[String]): Unit = {
		val roles: List[String] = ConfigFactory.load()
			.getStringList("akka.cluster.roles")
			.asScala.toList

		(port match {
			case Some(p) => Seq(p.toInt)
			case None => ConfigFactory.load()
				.getStringList("media-manage-state.node.port")
				.asScala.toList.map(p => p.toInt)
		}).view.zipWithIndex.foreach { case (nodePort, i) =>

			val system = ActorSystem[Nothing](
				media.state.guards.StateGuardian(),
				"media",
				ConfigFactory.parseString(s"""
				akka.cluster.roles.0 = ${roles(i)}
				akka.remote.artery.canonical.port = $nodePort
				"""
			).withFallback(ConfigFactory.load()))
			
			import akka.management.scaladsl.AkkaManagement

			if(i == 0)
				AkkaManagement(system).start()

			if(Cluster(system).selfMember.hasRole("read-model"))
				CassandraDB.createTables(system)
		}
	}
}
