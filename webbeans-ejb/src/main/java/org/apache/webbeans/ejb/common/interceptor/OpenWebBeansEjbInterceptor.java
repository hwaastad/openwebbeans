/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.ejb.common.interceptor;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.PostActivate;
import javax.ejb.PrePassivate;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.Decorator;
import javax.interceptor.AroundInvoke;
import javax.interceptor.AroundTimeout;
import javax.interceptor.InvocationContext;

import org.apache.webbeans.config.OWBLogConst;
import org.apache.webbeans.config.OpenWebBeansConfiguration;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.context.ContextFactory;
import org.apache.webbeans.context.creational.CreationalContextImpl;
import org.apache.webbeans.corespi.ServiceLoader;
import org.apache.webbeans.decorator.DelegateHandler;
import org.apache.webbeans.decorator.WebBeansDecoratorConfig;
import org.apache.webbeans.decorator.WebBeansDecoratorInterceptor;
import org.apache.webbeans.ejb.common.component.BaseEjbBean;
import org.apache.webbeans.inject.OWBInjector;
import org.apache.webbeans.intercept.InterceptorData;
import org.apache.webbeans.intercept.InterceptorDataImpl;
import org.apache.webbeans.intercept.InterceptorType;
import org.apache.webbeans.intercept.InterceptorUtil;
import org.apache.webbeans.intercept.InvocationContextImpl;
import org.apache.webbeans.logger.WebBeansLogger;
import org.apache.webbeans.proxy.JavassistProxyFactory;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.util.SecurityUtil;
import org.apache.webbeans.util.WebBeansUtil;

/**
 * EJB interceptor that is responsible
 * for injection dependent instances, and call
 * OWB based interceptors and decorators.
 * 
 * @version $Rev$ $Date$
 *
 */
    
public class OpenWebBeansEjbInterceptor implements Serializable
{
    private static final long serialVersionUID = -4317127341083031217L;

    //Logger instance
    private final WebBeansLogger logger = WebBeansLogger.getLogger(OpenWebBeansEjbInterceptor.class);
    
    /**Thread local for calling bean*/
    private static transient ThreadLocal<BaseEjbBean<?>> threadLocal = new ThreadLocal<BaseEjbBean<?>>();
    
    /**Thread local for calling creational context*/
    private static transient ThreadLocal<CreationalContext<?>> threadLocalCreationalContext = new ThreadLocal<CreationalContext<?>>();
    
    /**Intercepted methods*/
    protected transient Map<Method, List<InterceptorData>> interceptedMethodMap = new WeakHashMap<Method, List<InterceptorData>>();

    /**Non contextual Intercepted methods*/
    protected transient Map<Method, List<InterceptorData>> nonCtxInterceptedMethodMap = new WeakHashMap<Method, List<InterceptorData>>();
    
    /**Injector*/
    private transient OWBInjector injector;
    
    /**Resolved ejb beans for non-contexctual interceptors*/
    private transient Map<Class<?>, BaseEjbBean<?>> resolvedBeans = new HashMap<Class<?>, BaseEjbBean<?>>();
    
    /** The CreationalContext for the life of this interceptor, to create 299 interceptors and decorators */
    private CreationalContext<?> cc;
    
    /** Cached reference to the managed bean representing the EJB impl class this interceptor is associated with */
    private transient BaseEjbBean<?> contextual; 
    
    /** cache of associated BeanManagerImpl */
    private transient BeanManagerImpl manager;
    
    /* EJB InvocationContext.getTarget() provides underlying bean instance, which we do not want to hold a reference to */
    private CreationalKey ccKey;
    /**
     * Creates a new instance.
     */
    public OpenWebBeansEjbInterceptor()
    {
        
    }
    
    /**
     * Sets thread local.
     * @param ejbBean bean
     * @param creationalContext context
     */
    public static void setThreadLocal(BaseEjbBean<?> ejbBean, CreationalContext<?> creationalContext)
    {
        threadLocal.set(ejbBean);
        threadLocalCreationalContext.set(creationalContext);
    }
    
    /**
     * Remove locals.
     */
    public static void unsetThreadLocal()
    {
        threadLocal.set(null);
        threadLocalCreationalContext.set(null);
        
        threadLocal.remove();
        threadLocalCreationalContext.remove();
    }
    
    /**
     * Called for every business methods.
     * @param ejbContext invocation context
     * @return instance
     * @throws Exception
     */
    @AroundInvoke
    public Object callToOwbInterceptors(InvocationContext ejbContext) throws Exception
    {
        boolean requestCreated = false;
        boolean applicationCreated = false;
        boolean requestAlreadyActive = false;
        boolean applicationAlreadyActive = false;
       
        if (logger.wblWillLogDebug())
        { 
            logger.debug("Intercepting EJB method {0} ", ejbContext.getMethod());
        }
        
        try
        {
            if (OpenWebBeansConfiguration.getInstance().isUseEJBInterceptorActivation()) //default is true
            {
                int result = activateContexts(RequestScoped.class);
                //Context activities
                if(result == 1)
                {
                    requestCreated = true;
                }
                else if(result == -1)
                {
                    requestAlreadyActive = true;
                }
               
                result = activateContexts(ApplicationScoped.class);
                if(result == 1)
                {
                    applicationCreated = true;
                }
                else if(result == -1)
                {
                    applicationAlreadyActive = true;
                }
            }
            
            if (this.contextual == null)
            {
                return ejbContext.proceed();
            }
            
            return callInterceptorsAndDecorators(ejbContext.getMethod(), ejbContext.getTarget(), ejbContext.getParameters(), ejbContext);
        }
        finally
        {
            if (OpenWebBeansConfiguration.getInstance().isUseEJBInterceptorActivation()) 
            {
                if(!requestAlreadyActive)
                {
                    deActivateContexts(requestCreated, RequestScoped.class);   
                }
                if(!applicationAlreadyActive)
                {
                    deActivateContexts(applicationCreated, ApplicationScoped.class);   
                }
            }
            
        }
    }

    public void lifecycleCommon(InvocationContext context, InterceptorType interceptorType) 
    { 
        try
        {
            if ((this.contextual != null) && WebBeansUtil.isContainsInterceptorMethod(this.contextual.getInterceptorStack(), interceptorType))
            {
                InvocationContextImpl impl = new InvocationContextImpl(this.contextual, context.getTarget(), null, null, 
                        InterceptorUtil.getInterceptorMethods(this.contextual.getInterceptorStack(), interceptorType), interceptorType);
                impl.setCreationalContext(this.cc);
                impl.setEJBInvocationContext(context); // If the final 299 interceptor calls ic.proceed, the InvocationContext calls the ejbContext.proceed()
                impl.setCcKey(this.ccKey);
                impl.proceed();
            }
            else 
            { 
                context.proceed(); // no 299 interceptors    
            }
        }
        catch (Exception e)
        {
            logger.error(OWBLogConst.ERROR_0008, e, interceptorType);
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Post construct.  
     * 
     * This is called once per SFSB reference given out by the container,
     * but just once for the lifetime of an underlying stateless bean.  
     * 
     * This implies that interceptors of stateless beans must act stateless.
     * 
     * @param context invocation ctx
     */
    @PostConstruct
    public void afterConstruct(InvocationContext context)
    {
        if (logger.wblWillLogDebug())
        {
            logger.debug("entry");
        }

        if (this.manager == null) 
        {
            this.manager = BeanManagerImpl.getManager();
        }

        BaseEjbBean<?> injectionTarget = threadLocal.get();
        this.ccKey = new CreationalKey();
        
        
        if (injectionTarget == null)
        {
            // non-contextual, so we'll need to create and hold a CreationalContext
            this.contextual = findTargetBean(context.getTarget());
            if (this.contextual == null)
            {
                // We can't proceed if we didn't discover this EJB during scanning
                try
                {
                    context.proceed();
                }
                catch (Exception e) 
                {
                    throw new RuntimeException(e);
                }
                return;
            }
        }
        else 
        {
            this.contextual = injectionTarget;
            unsetThreadLocal(); // no longer needed
        }

        // Even for contextuals, we want to manage it along with THIS intereptor instance (e.g. SLSB)
        this.cc = manager.createCreationalContext(this.contextual);
        
        if (logger.wblWillLogDebug())
        {
            logger.debug("manager = {0} interceptor_instance = {1} contextual = {2} ", 
                    new Object[] { this.manager, this, this.contextual});
        }
        
        lifecycleCommon(context, InterceptorType.POST_CONSTRUCT);
        
        if (OpenWebBeansConfiguration.getInstance().isUseEJBInterceptorInjection())
        {
            Object instance = context.getTarget();
            this.injector = new OWBInjector();
            try
            {
                this.injector.inject(instance, this.cc);
            }
            catch (Exception e)
            {
                logger.error(OWBLogConst.ERROR_0026, e, this.contextual);
            }
        }
    }

    /**
     * Pre destroy.
     * 
     * @param context invocation context
     */
    @PreDestroy
    public void preDestroy(InvocationContext context)
    {

        lifecycleCommon(context, InterceptorType.PRE_DESTROY);

        if (this.injector != null)
        {
            this.injector.destroy();
        }

        this.interceptedMethodMap.clear();
        this.resolvedBeans.clear();
        this.nonCtxInterceptedMethodMap.clear();
        

        // Release the CC that lives as long as our interceptor instance
        this.cc.release();
        
    }

    /**
     * Activate given context.
     * @param scopeType scope type
     * @return true if also creates context.
     */
    private int activateContexts(Class<? extends Annotation> scopeType)
    {
        ContextsService service = ServiceLoader.getService(ContextsService.class);
        Context ctx = service.getCurrentContext(scopeType);
        
        if(scopeType == RequestScoped.class)
        {
            if(ctx != null && !ctx.isActive())
            {
                ContextFactory.activateContext(scopeType);
                return 0;
            }
            else if(ctx == null)
            {
                ContextFactory.initRequestContext(null);
                return 1;
            }
            
        }
        
        ctx = service.getCurrentContext(scopeType);
        if(ctx != null && !ctx.isActive())
        {
            ContextFactory.activateContext(scopeType);
            return 0;
        }
        else if(ctx == null)
        {
            ContextFactory.initApplicationContext(null);
            return 1;

        }     
        
        return -1;
    }
    
    /**
     * Deacitvate context.
     * @param destroy if destroy context
     * @param scopeType scope type
     */
    private void deActivateContexts(boolean destroy, Class<? extends Annotation> scopeType)
    {
        if(scopeType == ApplicationScoped.class)
        {
            if(destroy)
            {
                ContextFactory.destroyApplicationContext(null);
            }
            else
            {
                ContextFactory.deActivateContext(ApplicationScoped.class);
            }            
        }
        else
        {
            if(destroy)
            {
                ContextFactory.destroyRequestContext(null);
            }
            else
            {
                ContextFactory.deActivateContext(RequestScoped.class);
            }            
        }                
    }
    /**
     * Find the ManagedBean that corresponds to an instance of an EJB class
     * @param instance an instance of a class whose corresponding Managed Bean is to be searched for
     * @return the corresponding BaseEjbBean, null if not found
     */
    private BaseEjbBean<?> findTargetBean(Object instance) 
    {
        if (instance == null)
        {
            return null;
        }

        BaseEjbBean<?> ejbBean = this.resolvedBeans.get(instance.getClass());
        
        //Not found
        if(ejbBean == null)
        {
            Set<Bean<?>> beans = manager.getComponents();
            for(Bean<?> bean : beans)
            {
                if(bean instanceof BaseEjbBean)
                {
                    if(bean.getBeanClass() == instance.getClass())
                    {
                        ejbBean = (BaseEjbBean<?>)bean;
                        if (logger.wblWillLogDebug())
                        {
                            logger.debug("Found managed bean for [{0}] [{1}]", instance.getClass(), ejbBean);
                        }
                        this.resolvedBeans.put(instance.getClass(), ejbBean);
                        break;
                    }
                }
            }
        }        
        else 
        {
                if (logger.wblWillLogDebug()) 
                {
                    logger.debug("Managed bean for [{0}] found in cache: [{1}]", instance.getClass(),  ejbBean);
                }
        }
        
        return ejbBean;
    }
    
    /**
     * Calls OWB related interceptors and decorators.  
     * 
     * The underlying EJB context is called by our own ic.proceed at the top of the 299 stack, 
     * or by the Delegate handler if there are decorators (the DecoratorInterceptor does not call our ic.proceed, so the EJB proceed is not called twice)
     * 
     * @param method business method
     * @param instance bean instance
     * @param arguments method arguments
     * @return result of operation
     * @throws Exception for any exception
     */
    private Object callInterceptorsAndDecorators(Method method, Object instance, Object[] arguments, InvocationContext ejbContext) throws Exception
    {
        Object rv = null;
        BaseEjbBean<?> injectionTarget = this.contextual;
        InterceptorDataImpl decoratorInterceptorDataImpl = null;
        List<Object> decorators = null;
        DelegateHandler delegateHandler = null;
        List<Decorator<?>> decoratorStack = injectionTarget.getDecoratorStack();
        List<InterceptorData> interceptorStack = injectionTarget.getInterceptorStack();


        if (logger.wblWillLogDebug())
        {
            logger.debug("Decorator stack for target {0}", decoratorStack);
            logger.debug("Interceptor stack {0}", interceptorStack);
        }
                    
        if (decoratorStack.size() > 0 )
        {    
            if (logger.wblWillLogDebug())
            {
                logger.debug("Obtaining a delegate");
            }
            Class<?> proxyClass = JavassistProxyFactory.getInstance().getInterceptorProxyClasses().get(injectionTarget);
            if (proxyClass == null)
            {
                ProxyFactory delegateFactory = JavassistProxyFactory.getInstance().createProxyFactory(injectionTarget);
                proxyClass = JavassistProxyFactory.getInstance().getProxyClass(delegateFactory);
                JavassistProxyFactory.getInstance().getInterceptorProxyClasses().put(injectionTarget, proxyClass);
            }
            Object delegate = proxyClass.newInstance();
            delegateHandler = new DelegateHandler(this.contextual, ejbContext);
            ((ProxyObject)delegate).setHandler(delegateHandler);
     
            // Gets component decorator stack
            decorators = WebBeansDecoratorConfig.getDecoratorStack(injectionTarget, instance, delegate,
                                                                   (CreationalContextImpl<?>)this.cc);          
            
            //Sets decorator stack of delegate
            delegateHandler.setDecorators(decorators);
        }
        
        if (interceptorStack.size() == 0)
        {   
            if (decoratorStack.size() == 0)
            {
                rv = ejbContext.proceed();
            }
            else 
            {
                // We only have decorators, so run the decorator stack directly without interceptors. 
                // The delegate handler knows about the ejbContext.proceed()
                rv = delegateHandler.invoke(instance, method, null, arguments);    
            }
        }
        else 
        {
            // We have at least one interceptor.  Our delegateHandler will need to be wrapped in an interceptor.
           
            if (this.interceptedMethodMap.get(method) == null)
            {
                //Holds filtered interceptor stack
                List<InterceptorData> filteredInterceptorStack = new ArrayList<InterceptorData>(interceptorStack);

                // Filter both EJB and WebBeans interceptors
                InterceptorUtil.filterCommonInterceptorStackList(filteredInterceptorStack, method);
                InterceptorUtil.filterOverridenAroundInvokeInterceptor(injectionTarget.getBeanClass(), filteredInterceptorStack);

                this.interceptedMethodMap.put(method, filteredInterceptorStack);
            }
            List<InterceptorData> filteredInterceptorStack = new ArrayList<InterceptorData>(this.interceptedMethodMap.get(method));
            
            if (delegateHandler != null)
            {
                WebBeansDecoratorInterceptor lastInterceptor = new WebBeansDecoratorInterceptor(delegateHandler, instance);
                decoratorInterceptorDataImpl = new InterceptorDataImpl(true, lastInterceptor);
                decoratorInterceptorDataImpl.setDefinedInInterceptorClass(true);
                decoratorInterceptorDataImpl.setAroundInvoke(SecurityUtil.doPrivilegedGetDeclaredMethods(lastInterceptor.getClass())[0]);
                filteredInterceptorStack.add(decoratorInterceptorDataImpl);
            }
            
            // Call Around Invokes, 
            //      If there were decorators, the DelegatHandler will handle the  ejbcontext.proceed at the top of the stack.
            //      If there were no decorators, we will fall off the end of our own InvocationContext and take care of ejbcontext.proceed.
            rv = InterceptorUtil.callAroundInvokes(this.contextual, instance, (CreationalContextImpl<?>) this.cc, method, 
                    arguments, InterceptorUtil.getInterceptorMethods(filteredInterceptorStack, InterceptorType.AROUND_INVOKE), ejbContext, this.ccKey);
        }
        
        return rv;
    }

    /**
     * Around Timeout.
     * @param context invocation ctx
     */
    @AroundTimeout
    public Object callAroundTimeouts(InvocationContext context) throws Exception
    {
        Object rv = null;
        if (logger.wblWillLogTrace())
        {
            logger.debug("OWBEI:: @AroundTimeout entry. Trying to run Interceptors.");            
        }

        if ((this.contextual != null) && WebBeansUtil.isContainsInterceptorMethod(this.contextual.getInterceptorStack(), InterceptorType.AROUND_TIMEOUT))
        {           
            try
            {
                    InvocationContextImpl impl = new InvocationContextImpl(null, context.getTarget(), null, null, 
                            InterceptorUtil.getInterceptorMethods(this.contextual.getInterceptorStack(), InterceptorType.AROUND_TIMEOUT), InterceptorType.AROUND_TIMEOUT);
                    impl.setCreationalContext(this.cc);
                    impl.setEJBInvocationContext(context);
                    impl.setCcKey((Object)this.ccKey);
                    
                    rv = impl.proceed(); //run OWB interceptors and underlying EJBcontext.proceed()
            }
            catch (Exception e)
            {
                logger.error(OWBLogConst.ERROR_0008, e, "@AroundTimeout.");    
                throw new RuntimeException(e);
            }                      
        }
        else 
        { 
            rv = context.proceed(); // no 299 interceptors
        }
        
        return rv;
    }

    
    
    /**
     * PrePassivate callback
     */
    @PrePassivate
    public void beforePassivate(InvocationContext context)
    {
        if (logger.wblWillLogDebug())
        {
            logger.debug("manager = {0} interceptor_instance = {1} contextual = {2} ", 
                    new Object[] { this.manager, this, this.contextual});
        }
        lifecycleCommon(context, InterceptorType.PRE_PASSIVATE);
    }

    /**
     * PostActivate callback
     */
    @PostActivate
    public void afterActivate(InvocationContext context)
    {
        // lost during activation
        this.contextual = findTargetBean(context.getTarget());

        if (logger.wblWillLogDebug())
        {
            logger.debug("manager = {0} interceptor_instance = {1} contextual = {2} ", 
                    new Object[] { this.manager, this, this.contextual});
        }

        lifecycleCommon(context, InterceptorType.POST_ACTIVATE);
    }

    private void writeObject(ObjectOutputStream out) throws IOException
    {
        if (logger.wblWillLogDebug())
        {
            logger.debug("manager = {0} interceptor_instance = {1} contextual = {2} ", 
                          new Object[] { this.manager, this, this.contextual});
        }
        
        /* notably our stashed CreationalContext */
        out.defaultWriteObject();
    }
    
    
    private  void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException
    {
        if (logger.wblWillLogDebug())
        {
            logger.debug("interceptor instance = " + this.hashCode());
        }

        /* rebuild transient maps */
        interceptedMethodMap = new WeakHashMap<Method, List<InterceptorData>>();
        nonCtxInterceptedMethodMap = new WeakHashMap<Method, List<InterceptorData>>();
        resolvedBeans = new HashMap<Class<?>, BaseEjbBean<?>>();

        /* notably our stashed CreationalContext */
        s.defaultReadObject();

        /* restore transient BeanManager */
        this.manager = BeanManagerImpl.getManager();

        if (logger.wblWillLogDebug())
        {
            logger.debug("manager = {0} interceptor_instance = {1} contextual = {2} ", 
                          new Object[] { this.manager, this, this.contextual});
        }
        
    }

    /**
     * Our key into the CreationalContext, instead of ejbContext.getTarget();
     * 
     */
    private class CreationalKey implements Serializable 
    {

    }

}
