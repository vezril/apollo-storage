package apollostorage.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The cluster runtime config wires a mandatory split-brain resolver and resolves sharding +
 * projection sizing from config with env overrides (design D27/D30).
 */
final class ClusterConfigSpec extends AnyWordSpec with Matchers:

  private def resolved = ConfigFactory.parseResources("cluster.conf").resolve()

  "cluster.conf" should {

    "configure the split-brain resolver (keep-majority)" in {
      resolved.getString("pekko.cluster.downing-provider-class") should
        include("SplitBrainResolverProvider")
      resolved.getString("pekko.cluster.split-brain-resolver.active-strategy") shouldBe
        "keep-majority"
    }

    "default sharding and projection sizing" in {
      resolved.getInt("pekko.cluster.sharding.number-of-shards") shouldBe 256
      resolved.getInt("apollostorage.projection.instances") shouldBe 4
    }

    "honor env overrides for shard count and projection instances" in {
      val overridden = ConfigFactory
        .parseString("CLUSTER_NUMBER_OF_SHARDS = 512\nPROJECTION_INSTANCES = 8")
        .withFallback(ConfigFactory.parseResources("cluster.conf"))
        .resolve()
      overridden.getInt("pekko.cluster.sharding.number-of-shards") shouldBe 512
      overridden.getInt("apollostorage.projection.instances") shouldBe 8
    }
  }
