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
package org.apache.webbeans.resource.spi.ee;

import org.apache.webbeans.annotation.DefaultLiteral;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.resource.spi.se.StandaloneResourceInjectionService;
import org.apache.webbeans.spi.api.ResourceReference;

import javax.ejb.EJB;
import javax.enterprise.inject.spi.Bean;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.lang.annotation.Annotation;
import java.util.Iterator;

/**
 * Allows to use @EJB in JEE 5 app servers
 */
public class ExtendedStandaloneResourceInjectionService extends StandaloneResourceInjectionService
{

    public ExtendedStandaloneResourceInjectionService(WebBeansContext webBeansContext)
    {
        super(webBeansContext);
    }

    @Override
    public <X, T extends Annotation> X getResourceReference(ResourceReference<X, T> resourceReference)
    {
        if (resourceReference.supports(EJB.class))
        {
            String jndiName = convertToJndiName(resourceReference.getResourceType());
            X result = lookupRemoteEjb(jndiName, resourceReference.getResourceType());

            return result;
        }

        return super.getResourceReference(resourceReference);
    }

        private String convertToJndiName(Class resourceType)
    {
        return resourceType.getSimpleName() + "#" + resourceType.getName();
    }

    private <X> X lookupRemoteEjb(String jndiName, Class<X> resourceType)
    {
        try
        {
            Context context = new InitialContext();
            X result = (X) context.lookup(jndiName);
            return result;
        }
        catch (NamingException e)
        {
            //fallback for a servlet container
            BeanManagerImpl beanManager = getWebBeansContext().getBeanManagerImpl();

            Iterator<Bean<?>> beansIterator = beanManager.getBeans(resourceType, new DefaultLiteral()).iterator();

            if (!beansIterator.hasNext())
            {
                e.printStackTrace();
                throw new RuntimeException("can't find ejb (via jndi) or cdi bean for type "
                        + resourceType.getName(), e);
            }
            Bean<?> simulatedStatelessEjbBean = beansIterator.next();

            return (X)beanManager.getReference(simulatedStatelessEjbBean,
                                               resourceType,
                                               beanManager.createCreationalContext(simulatedStatelessEjbBean));
        }
    }
}