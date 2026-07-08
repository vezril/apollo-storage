package apollostorage

import apollostorage.persistence.BucketSharding
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Join}

/**
 * Test helper: single-node cluster config + formation, so Cluster Sharding is available in in-JVM
 * tests (multiple systems per JVM allowed).
 */
object ClusterTestSupport:

  val clusterConfig: Config = ConfigFactory.parseString(
    """
    pekko.actor.provider = "cluster"
    pekko.remote.artery.canonical.hostname = "127.0.0.1"
    pekko.remote.artery.canonical.port = 0
    pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
    pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
    """
  )

  /** Join the node to itself, wait for `Up`, and initialize bucket sharding. */
  def formSingleNode(system: ActorSystem[?]): ClusterSharding =
    val cluster = Cluster(system)
    cluster.manager ! Join(cluster.selfMember.address)
    var remaining = 100
    while cluster.selfMember.status != MemberStatus.Up && remaining > 0 do
      Thread.sleep(100)
      remaining -= 1
    BucketSharding.init(system)
