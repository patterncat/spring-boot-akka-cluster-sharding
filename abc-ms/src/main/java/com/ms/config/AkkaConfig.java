package com.ms.config;

import akka.actor.*;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ClusterShardingSettings;
import akka.cluster.sharding.ShardRegion;
import akka.japi.Function;
import akka.japi.Option;
import akka.routing.RoundRobinPool;
import com.ms.abc.service.DataStoreException;
import com.ms.abc.service.ServiceUnavailable;
import com.ms.event.AssignmentEvent;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.Scope;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import scala.concurrent.duration.Duration;

import java.util.Optional;

import static akka.actor.SupervisorStrategy.*;

@Configuration
//@Lazy
/*
@Lazy
@ComponentScan(basePackages = {"com.cgi.garnet.attachment.config",
        "com.cgi.garnet.attachment.rest", "com.cgi.garnet.attachment.service"})
*/
public class AkkaConfig extends WebMvcConfigurerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(AkkaConfig.class);


    @Autowired
    private SpringExtension springExtension;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Actor system singleton for this application.
     */
    @Bean
    public ActorSystem actorSystem() {
        ActorSystem actorSystem = ActorSystem.create("ClusterSystem", akkaConfiguration());
        springExtension.initialize(applicationContext);
        return actorSystem;
    }

    @Bean
    public ActorRef publishToDefActor() {
        return actorSystem().actorOf(springExtension.props("publishToDef").withRouter(new RoundRobinPool(2)), "publishToDef");
    }

    @Bean
    public ActorRef publishToSelfActor() {
        return actorSystem().actorOf(springExtension.props("publishToSelf").withRouter(new RoundRobinPool(2)), "publishToSelf");
    }


    @Bean
    public ClusterShardingSettings initClusterShardingSettings(){
        return ClusterShardingSettings.create(actorSystem()).withRole("abcService");
    }


    @Bean
    public ClusterSharding clusterSharding(){
        return  ClusterSharding.get(actorSystem());
    }



    @Bean
    public Props abcEventStoreSupervisorProps(){
        return springExtension.props("abcEventStoreSupervisor");
    }

    @Bean
    public Props abcEventListenerProps(){
        return springExtension.props("abcEventListener");
    }

    @Bean
    public Props abcEventStoreActorProps(){
        return springExtension.props("abcEventStoreActor");
    }


    /**
     * Always start the top supervisor. Let the supervisor create it's own children in this case listerSuperVisor has Listener Actor and listener Actor it self is a supervisor for worker.
     * @return
     */

    @Bean
    public ActorRef initAbcEventStoreSupervisor() {
        ActorRef sub = actorSystem().actorOf(abcEventStoreSupervisorProps(), "abcEventStoreSupervisor");
        return sub;

    }

    @Bean
    public ActorRef initAbcEventListener() {
        ActorRef sub = actorSystem().actorOf(abcEventListenerProps(), "abcEventListener");
        return sub;

    }


    @Bean
    public ActorRef abcEventStoreActorShardRegion() {
        return clusterSharding().start("abcEventStoreActor", abcEventStoreActorProps(), initClusterShardingSettings(), abcShardignessageExtractor());
    }


    @Bean
    public ActorRef abcEventStoreSupervisorShardRegion() {
        return clusterSharding().start("abcEventStoreSupervisor", abcEventStoreSupervisorProps(), initClusterShardingSettings(), abcShardignessageExtractor());
    }
    /*
    This optional if we need to send messages directly to the def service probably we can use the proxy approach. This example mainly demonstrates pub/sub model.
     */
    @Bean
    public ActorRef defEventStoreActorShardRegion() {
        return clusterSharding().startProxy("defEventStoreActor", Optional.of("defService"), abcShardignessageExtractor());
    }

    /*
    This optional if we need to send messages directly to the def service probably we can use the proxy approach. This example mainly demonstrates pub/sub model.
     */
    @Bean
    public ActorRef defEventStoreSupervisorShardRegion() {
        return clusterSharding().startProxy("defEventStoreSupervisor",Optional.of("defService"),abcShardignessageExtractor());
    }

    @Bean
    @Scope(value = "prototype")
    public ShardRegion.MessageExtractor abcShardignessageExtractor() {
        ShardRegion.MessageExtractor  messageExtractor = new ShardRegion.MessageExtractor() {
            @Override
            public Object entityMessage(Object message) {
                return message;
            }

            @Override
            public String entityId(Object message) {
                if (message instanceof AssignmentEvent) {
                    String id=((AssignmentEvent) message).getModuleId().toString();
                    return id;
                }
                return  null;
            }
            @Override
            public String shardId(Object message) {
                int numberOfShards = 100;
                if (message instanceof AssignmentEvent) {
                    String uid = ((AssignmentEvent) message).getModuleId().toString();
                    String shardId=String.valueOf(uid.length() % numberOfShards);;
                    System.out.println("ShardId --->" + shardId);
                    return shardId;
                } else {
                    System.out.println("ShardId is null ??????????????????????");
                    return null;
                }
            }

        };
        return messageExtractor;
    }


    @Bean
    // Restart the child when ServiceUnavailable is thrown.
    // After 3 restarts within 5 seconds it will escalate to the supervisor which may stop the process.
    public SupervisorStrategy restartOrEsclate() {
        SupervisorStrategy strategy = new OneForOneStrategy(-1,Duration.create("5 seconds"), new Function<Throwable, SupervisorStrategy.Directive>() {
            @Override
            public SupervisorStrategy.Directive apply(Throwable t) {
                if (t instanceof NullPointerException) {
                    System.out.println("oneToOne: restartOrEsclate strategy, restarting the actor");
                    return restart();
                }else if (t instanceof ServiceUnavailable) {
                    System.out.println("oneToOne: restartOrEsclate strategy, escalate");
                    return escalate();
                }else if (t instanceof DataStoreException) {
                    System.out.println("oneToOne: DataStoreException invoked, escalating to oneToAll @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
//                    return restart();
                    return stop();
                }  else {
                    System.out.println("oneToOne: final else called escalating to oneToAll");
                    return escalate();
                }
            }
        });
        return strategy;
    }

    /**
     * Read configuration from application.conf file
     */
    @Bean
    public Config akkaConfiguration() {
        return ConfigFactory.load();
    }

}
