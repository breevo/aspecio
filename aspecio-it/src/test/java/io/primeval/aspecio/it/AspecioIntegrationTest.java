package io.primeval.aspecio.it;

import static io.primeval.aspecio.it.TestProvisioningConfig.baseOptions;
import static io.primeval.aspecio.it.TestProvisioningConfig.dsAndFriends;
import static io.primeval.aspecio.it.TestProvisioningConfig.slf4jLogging;
import static io.primeval.aspecio.it.TestProvisioningConfig.testingBundles;
import static org.ops4j.pax.exam.Constants.START_LEVEL_TEST_BUNDLE;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.frameworkStartLevel;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.Hashtable;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.promise.Promise;
import org.osgi.util.tracker.ServiceTracker;

import io.primeval.aspecio.Aspecio;
import io.primeval.aspecio.AspecioConstants;
import io.primeval.aspecio.AspectDescription;
import io.primeval.aspecio.examples.DemoConsumer;
import io.primeval.aspecio.examples.aspect.counting.CountingAspect;
import io.primeval.aspecio.examples.aspect.metric.MetricAspect;
import io.primeval.aspecio.examples.async.SuperSlowService;
import io.primeval.aspecio.examples.greetings.Goodbye;
import io.primeval.aspecio.examples.greetings.Hello;
import io.primeval.aspecio.it.testset.api.Randomizer;
import io.primeval.aspecio.it.testset.aspect.NoopAspect;
import io.primeval.aspecio.it.testset.component.RandomizerImpl;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class AspecioIntegrationTest {

    @Inject
    private BundleContext bundleContext;

    @Inject
    private Aspecio aspecio;

    public static Option exampleApplication() {
        return composite(dsAndFriends(),
                mavenBundle("com.google.guava", "guava").versionAsInProject(),
                mavenBundle("io.primeval.aspecio", "aspecio-examples").versionAsInProject());
    }

    @Configuration
    public Option[] config() throws Throwable {
        return new Option[] {
                baseOptions(),
                testingBundles(),
                slf4jLogging(),
                mavenBundle("org.ow2.asm", "asm").version("5.2"),
                mavenBundle("io.primeval", "primeval-reflex").versionAsInProject(),
                mavenBundle("io.primeval.aspecio", "aspecio-core").versionAsInProject(),
                exampleApplication(),
                frameworkStartLevel(START_LEVEL_TEST_BUNDLE)
        };
    }

    @Test
    public void testExampleApplication() throws Exception {

        checkExampleApplicationIsProperlyWoven();

        // Try to restart Aspecio to demonstrate Aspecio's ability to install at any time

        Bundle aspecioBundle = getAspecioBundle();

        aspecioBundle.stop();
        aspecioBundle.start();

        checkExampleApplicationIsProperlyWoven();

    }

    private Bundle getAspecioBundle() {
        return Stream.of(bundleContext.getBundles())
                .filter(b -> b.getSymbolicName().equals("io.primeval.aspecio")).findFirst().orElse(null);
    }

    private void checkExampleApplicationIsProperlyWoven()
            throws InterruptedException, UnsupportedEncodingException, IOException, InvocationTargetException {
        // Check if all aspects are accounted for
        Set<String> aspects = aspecio.getRegisteredAspects();
        assertThat(aspects).contains(MetricAspect.All.class.getName(), MetricAspect.AnnotatedOnly.class.getName(),
                CountingAspect.class.getName());

        Optional<AspectDescription> aspectDescription = aspecio.getAspectDescription(MetricAspect.All.class.getName());
        assertThat(aspectDescription).isPresent();
        assertThat(aspectDescription.get().aspectName).isEqualTo(MetricAspect.All.class.getName());

        ServiceTracker<DemoConsumer, DemoConsumer> demoConsumerTracker = new ServiceTracker<>(bundleContext,
                DemoConsumer.class, null);
        demoConsumerTracker.open();

        // Test app.
        ServiceTracker<Hello, Hello> helloTracker = new ServiceTracker<>(bundleContext, Hello.class, null);
        helloTracker.open();

        ServiceTracker<Goodbye, Goodbye> goodbyeTracker = new ServiceTracker<>(bundleContext, Goodbye.class, null);
        goodbyeTracker.open();

        helloTracker.waitForService(10_000L);
        goodbyeTracker.waitForService(10_000L);
        DemoConsumer demoConsumer = demoConsumerTracker.waitForService(10_000L);
        

        // In our system, we have exactly one service, that is woven by Aspecio,
        // that provides both Hello and Goodbye.
        assertThat(helloTracker.getServiceReferences().length).isEqualTo(1);
        assertThat(goodbyeTracker.getServiceReferences().length).isEqualTo(1);

        ServiceReference<Hello> helloSr = helloTracker.getServiceReference();
        ServiceReference<Goodbye> goodbyeSr = goodbyeTracker.getServiceReference();

        // The following service references should be the same
        assertThat(helloSr).isEqualTo(goodbyeSr);

        ServiceReference<?> commonSr = helloSr;

        // Hidden property added to woven services
        Object wovenProperty = commonSr.getProperty(AspecioConstants._SERVICE_ASPECT_WOVEN);
        assertThat(wovenProperty).isNotNull().isInstanceOf(String[].class);
        assertThat((String[]) wovenProperty).containsExactly(CountingAspect.class.getName(),
                MetricAspect.All.class.getName());

        Hello hello = helloTracker.getService();
        Goodbye goodbye = goodbyeTracker.getService();

        assertThat(hello).isSameAs(goodbye);
        assertThat(hello.getClass().getName())
                .isEqualTo("io.primeval.aspecio.examples.greetings.internal.HelloGoodbyeImpl$Proxy$");

        hello.hello();

        ServiceTracker<SuperSlowService, SuperSlowService> slowTracker = new ServiceTracker<>(bundleContext,
                SuperSlowService.class, null);
        slowTracker.open();

        // Check that there is one shared classloader for woven aspects of objects of a same given bundlerevision
        assertThat(slowTracker.getService().getClass().getClassLoader()).isSameAs(hello.getClass().getClassLoader());

        slowTracker.close();
        helloTracker.close();
        goodbyeTracker.close();
        

        Promise<Long> longResult = demoConsumer.getLongResult();

        assertThat(extractFromPrintStream(ps -> demoConsumer.consumeTo(ps))).isEqualTo("hello goodbye\n");

        ServiceTracker<CountingAspect, CountingAspect> caTracker = new ServiceTracker<>(bundleContext,
                CountingAspect.class, null);
        caTracker.open();
        CountingAspect countingAspect = caTracker.getService();

        countingAspect.printCounts();

        assertThat(longResult.getValue()).isEqualTo(42L);

        caTracker.close();
        demoConsumerTracker.close();
    }

    @Test
    public void testAspectDynamicity() {

        ServiceTracker<Randomizer, Randomizer> rmdnTracker = new ServiceTracker<>(bundleContext, Randomizer.class,
                null);
        rmdnTracker.open();

        String fakeAspect = "tested.aspect";
        Hashtable<String, Object> serviceProps = new Hashtable<>();
        serviceProps.put(AspecioConstants.SERVICE_ASPECT_WEAVE, fakeAspect);
        RandomizerImpl randomizerImpl = new RandomizerImpl();
        ServiceRegistration<Randomizer> serviceReg = bundleContext.registerService(Randomizer.class, randomizerImpl,
                serviceProps);

        // Check that the service is not available, because our fakeAspect is not provided.
        assertThat(rmdnTracker.size()).isEqualTo(0);

        NoopAspect noopAspect = new NoopAspect();
        Hashtable<String, Object> aspectProps = new Hashtable<>();
        aspectProps.put(AspecioConstants.SERVICE_ASPECT, fakeAspect);
        ServiceRegistration<Object> aspectReg = bundleContext.registerService(Object.class, noopAspect, aspectProps);

        // Check that the service is available, because our fakeAspect is provided.
        assertThat(rmdnTracker.size()).isEqualTo(1);
        assertThat((String[]) rmdnTracker.getServiceReference().getProperty(AspecioConstants._SERVICE_ASPECT_WOVEN))
                .containsExactly(fakeAspect);

        aspectReg.unregister();
        // Check that the service is available, because our fakeAspect is gone.
        assertThat(rmdnTracker.size()).isEqualTo(0);

        // Register the aspect again
        aspectReg = bundleContext.registerService(Object.class, noopAspect, aspectProps);
        assertThat(rmdnTracker.size()).isEqualTo(1);

        // Let the service go
        serviceReg.unregister();
        assertThat(rmdnTracker.size()).isEqualTo(0);

        // Register the service again, it should be immediately available
        serviceReg = bundleContext.registerService(Randomizer.class, randomizerImpl, serviceProps);
        assertThat(rmdnTracker.size()).isEqualTo(1);

        rmdnTracker.close();

    }

    private String extractFromPrintStream(Consumer<PrintStream> psConsumer)
            throws UnsupportedEncodingException, IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); PrintStream ps = new PrintStream(baos)) {
            psConsumer.accept(ps);
            return baos.toString("UTF-8");
        }
    }
}