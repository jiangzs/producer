package com.jzs.vertx;

import com.hazelcast.config.Config;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.HystrixMetricHandler;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.ext.dropwizard.DropwizardMetricsOptions;
import io.vertx.ext.dropwizard.MetricsService;
import io.vertx.ext.web.Router;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import lombok.extern.slf4j.Slf4j;
import rx.Observable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by jiangzs@gmail.com on 2017/4/21.
 */
//@Slf4j(topic = "Product")
@Slf4j
public class ProducerVerticle extends AbstractVerticle {

    private final String EVENT_QUEUE = new String("work");

    public static void main(String[] args) {
        log.info("main start...");
        Config hazelcastConfig = new Config();

        hazelcastConfig.getNetworkConfig().getJoin().getTcpIpConfig().addMember("172.21.230.157").setEnabled(true);
//        hazelcastConfig.getNetworkConfig().getJoin().getTcpIpConfig().addMember("192.168.99.1").setEnabled(true);
        hazelcastConfig.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);


        ClusterManager mgr = new HazelcastClusterManager(hazelcastConfig);
        VertxOptions options = new VertxOptions().setClusterManager(mgr);

        DropwizardMetricsOptions metricsOptions = new DropwizardMetricsOptions();
        metricsOptions.setJmxEnabled(true);
        metricsOptions.setEnabled(true);

        options.setMetricsOptions(metricsOptions);

        Vertx.clusteredVertx(options, res -> {
            if (res.succeeded()) {
                Vertx vertx = res.result();
                DeploymentOptions doptions = new DeploymentOptions().setWorker(true);
                vertx.deployVerticle(new ProducerVerticle(), doptions);

                Router router = Router.router(vertx);
                router.get("/hystrix-metrics").handler(HystrixMetricHandler.create(vertx));
                vertx.createHttpServer()
                        .requestHandler(router::accept)
                        .listen(8081);
            }
        });
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        super.start(startFuture);

        final EventBus bus = getVertx().eventBus();
        final MetricsService metricsService = MetricsService.create(getVertx());

        final CircuitBreaker breaker = CircuitBreaker.create("Producer.circuitBreaker", getVertx(), new CircuitBreakerOptions().setTimeout(10))
                .openHandler(open ->
                        log.error("Producer circuitBreaker OPEN OPEN OPEN OPEN OPEN")
                )
                .closeHandler(close ->
                        log.error("Producer circuitBreaker CLOSE CLOSE CLOSE CLOSE CLOSE CLOSE")
                )
                .halfOpenHandler(half -> {
                    log.info("Producer circuitBreaker HALF HALF HALF HALF HALF HALF  ");
                });

        final Handler<AsyncResult<Message<String>>> eventHandler = event -> {
            if (event.succeeded()) {
                log.info("reply {}", event.result().body());
            } else {
                log.error("error why ? {} ", event.cause().getMessage());
            }
        };

        startFuture.compose((Void noFuture) -> {

            Observable.interval(1, TimeUnit.SECONDS).subscribe(s -> {

                final long _start  = System.currentTimeMillis();

                List<String> data = new ArrayList<String>();

                List<Future> results = new ArrayList<Future>();
                for (int i = 0; i < 11; i++) {
                    data.add(String.valueOf(s).concat("-").concat(String.valueOf(i)));
                    results.add(Future.future());
                }

                int size = data.size();
                for (int i = 0; i < size; i++) {
                    final int a = i;
                    bus.send(EVENT_QUEUE, data.get(i), event -> {
                        if (event.succeeded()) {
                            results.get(a).complete(event.result().body().toString());
                        } else {
                            results.get(a).fail(event.cause().getMessage());
                        }
                    });
                }

                CompositeFuture.all(results).setHandler(result -> {
                    if (result.succeeded()) {
                        StringBuffer sb = new StringBuffer();
                        for (Future future : results) {
                            sb.append(future.result());
                        }
                        log.info("source:{} time:{} result:{}", s, System.currentTimeMillis() -_start, sb.toString());
                    } else {
                        log.error("is any body miss source:{}", s);
                    }
                });

//                bus.send(EVENT_QUEUE, String.valueOf(s), event -> {
//                    breaker.<String>execute(command -> {
////                        log.info("bus handler id:{}", Thread.currentThread().getId());
//                        if (event.succeeded()) {
////                            log.info("reply {}", event.result().body());
//                            command.tryComplete(event.result().body().toString());
//                        } else {
////                            log.error("error why ? {} ", event.cause().getMessage());
//                            command.fail(event.cause().getMessage());
//                        }
//                    }).setHandler(ar -> {
//                        if (ar.succeeded()) {
//                            log.info("ar result:{}", ar.result());
//                        } else {
//                            log.error("ar cause:{}", ar.cause().getMessage());
//                        }
//                    });
//                });


            });

            log.info("Producer started id:{}", deploymentID());
        }, null);
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        super.stop(stopFuture);

        stopFuture.compose(event -> log.info("stoped ProducerVerticle ..." + deploymentID()), null);

    }
}