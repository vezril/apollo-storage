package apollostorage.cluster

import apollostorage.ClusterTestSupport
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.typed.Cluster
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * A single node forms a cluster of one and reaches `Up` (design D28). Genuine multi-host formation
 * is exercised by the deployment smoke, not in-process.
 */
final class ClusterFormationSpec
    extends ScalaTestWithActorTestKit(
      ClusterTestSupport.clusterConfig.withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers:

  "A single node" should {
    "form a cluster and reach Up" in {
      ClusterTestSupport.formSingleNode(system)
      Cluster(system).selfMember.status shouldBe MemberStatus.Up
    }
  }
