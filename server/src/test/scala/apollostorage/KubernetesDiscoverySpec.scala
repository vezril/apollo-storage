package apollostorage

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Deploy regression (v0.12.1): in Kubernetes the chart selects `DISCOVERY_METHOD=kubernetes-api`
 * for Cluster Bootstrap, so the `kubernetes-api` service-discovery method MUST resolve to a real
 * implementation — i.e. the `pekko-discovery-kubernetes-api` artifact and its reference.conf are on
 * the classpath. Without that dependency, `loadServiceDiscovery("kubernetes-api")` throws
 * `IllegalArgumentException: pekko.discovery.kubernetes-api.class must point to a FQN …`, which
 * crash-looped apollo-0 the first time Cluster Bootstrap ran (local/tests only ever used the
 * `config` method, so it was never exercised).
 */
final class KubernetesDiscoverySpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers:

  "kubernetes-api service discovery" should {
    // Assert the method is configured (its reference.conf is present) and the implementation class is
    // on the classpath — the two things the missing artifact provided. We do NOT instantiate it, since
    // KubernetesApiServiceDiscovery's constructor reads in-pod files (/var/run/secrets/...) absent off-cluster.
    "be configured and present on the classpath" in {
      val fqn = system.settings.config.getString("pekko.discovery.kubernetes-api.class")
      fqn should include("Kubernetes")
      noException should be thrownBy Class.forName(fqn)
    }
  }
