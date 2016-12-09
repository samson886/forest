package com.zhizus.forest.client.proxy;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.zhizus.forest.client.cluster.ClusterProvider;
import com.zhizus.forest.client.proxy.processor.*;
import com.zhizus.forest.common.annotation.MethodProvider;
import com.zhizus.forest.common.annotation.ServiceProvider;
import com.zhizus.forest.common.codec.Header;
import com.zhizus.forest.common.codec.Message;
import com.zhizus.forest.common.codec.Request;
import com.zhizus.forest.common.config.MethodConfig;
import com.zhizus.forest.common.config.ServiceConfig;
import com.zhizus.forest.registry.AbstractServiceDiscovery;
import com.zhizus.forest.registry.impl.LocalServiceDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by Dempe on 2016/12/7.
 */
public class ForestDynamicProxy implements InvocationHandler {

    private final static Logger LOGGER = LoggerFactory.getLogger(ForestDynamicProxy.class);

    private ServiceConfig config;

    private final static AtomicLong id = new AtomicLong(0);

    private String serviceName;

    private ClusterProvider clusterProvider;

    private AbstractServiceDiscovery registry;

    public Map<Method, Header> headerMapCache = Maps.newConcurrentMap();

    public ForestDynamicProxy(ServiceConfig serviceConfig, Class<?> interfaceClass, AbstractServiceDiscovery registry) throws Exception {
        this(interfaceClass, registry);
        for (Map.Entry<String, MethodConfig> methodConfigEntry : serviceConfig.getMethodConfigMap().entrySet()) {
            MethodConfig methodConfigFromAnnotation = config.getMethodConfig(methodConfigEntry.getKey());
            if (methodConfigFromAnnotation == null) {
                LOGGER.warn("methodName is not exist. err methodName:{},serviceName:{}", methodConfigEntry.getKey(), serviceName);
                continue;
            }
            MethodConfig methodConfig = methodConfigEntry.getValue();
            methodConfig.setMethodName(methodConfigFromAnnotation.getMethodName());
            methodConfig.setServiceName(methodConfigFromAnnotation.getServiceName());
            config.registerMethodConfig(methodConfigEntry.getKey(), methodConfig);
        }

    }

    public ForestDynamicProxy(Class<?> interfaceClass, AbstractServiceDiscovery registry) throws Exception {
        clusterProvider = new ClusterProvider();
        clusterProvider.init();
        config = ServiceConfig.Builder.newBuilder().build();
        AnnotationProcessorsProvider processors = AnnotationProcessorsProvider.DEFAULT;
        registerAnnotationProcessors(processors);
        ServiceProvider serviceProvider = interfaceClass.getAnnotation(ServiceProvider.class);
        this.serviceName = Strings.isNullOrEmpty(serviceProvider.serviceName()) ? interfaceClass.getSimpleName() : serviceProvider.serviceName();
        // 加载注解配置作为默认配置
        for (Method method : interfaceClass.getMethods()) {
            for (AnnotationProcessor processor : processors.getProcessors()) {
                processor.process(serviceName, method, config);
            }
        }
        config.setServiceName(serviceName);
        if (registry == null) {
            registry = AbstractServiceDiscovery.DEFAULT_DISCOVERY;
        }
        this.registry = registry;
        if (registry instanceof LocalServiceDiscovery) {
            registry.registerLocal(config.getServiceName(), ((LocalServiceDiscovery) registry).getAddress());
        }

    }

    public static void registerAnnotationProcessors(AnnotationProcessorsProvider processors) {
        processors.register(new HttpAnnotationProcessor());
        processors.register(new HystrixAnnotationProcessor());
        processors.register(new MethodProviderAnnotationProcessor());
    }

    public static <T> T newInstance(Class<T> clazz, ServiceConfig serviceConfig, AbstractServiceDiscovery registry) throws Exception {
        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{clazz}, new ForestDynamicProxy(serviceConfig, clazz, registry));
    }

    public static <T> T newInstance(Class<T> clazz, AbstractServiceDiscovery registry) throws Exception {
        Object instance = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[]{clazz}, new ForestDynamicProxy(clazz, registry));

        return (T) instance;
    }


    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodProvider methodProvider = method.getAnnotation(MethodProvider.class);
        if (methodProvider == null) {
            LOGGER.info("method:{} cannot find methodProvider.", method.getName());
            return null;
        }
        String methodName = Strings.isNullOrEmpty(methodProvider.methodName()) ? method.getName() : methodProvider.methodName();
        MethodConfig methodConfig = config.getMethodConfig(methodName);
        if (methodConfig == null) {
            LOGGER.info("serviceName:{},methodName is not found", serviceName, method.getName());
            return null;
        }
        Header header = headerMapCache.get(method);
        if (header == null) {
            header = Header.HeaderMaker.newMaker()
                    .loadWithMethodConfig(methodConfig)
                    .withMessageId(id.incrementAndGet()).make();

        }


        Message message = new Message(header,
                new Request(serviceName, methodName, args));
        return clusterProvider.call(message);

    }
}
