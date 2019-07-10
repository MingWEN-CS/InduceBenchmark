/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.server;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import org.apache.oozie.servlet.CallbackServlet;
import org.apache.oozie.servlet.SLAServlet;
import org.apache.oozie.servlet.V0AdminServlet;
import org.apache.oozie.servlet.V0JobServlet;
import org.apache.oozie.servlet.V0JobsServlet;
import org.apache.oozie.servlet.V1AdminServlet;
import org.apache.oozie.servlet.V1JobServlet;
import org.apache.oozie.servlet.V2AdminServlet;
import org.apache.oozie.servlet.V2JobServlet;
import org.apache.oozie.servlet.V2SLAServlet;
import org.apache.oozie.servlet.V2ValidateServlet;
import org.apache.oozie.servlet.VersionServlet;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.webapp.WebAppContext;

import javax.servlet.Servlet;


public class ServletMapper {
    private final WebAppContext servletContextHandler;

    @Inject
    public ServletMapper(final WebAppContext servletContextHandler) {
        this.servletContextHandler = Preconditions.checkNotNull(servletContextHandler, "ServletContextHandler is null");
    }
    /**
     * Maps Oozie servlets to path specs. Make sure it is in sync with FilterMapper when making changes.
     * */
    void mapOozieServlets() {
        mapServlet(VersionServlet.class, "/versions");
        mapServlet(V0AdminServlet.class, "/v0/admin/*");
        mapServlet(V1AdminServlet.class, "/v1/admin/*");
        mapServlet(V2AdminServlet.class, "/v2/admin/*");

        mapServlet(CallbackServlet.class, "/callback/*");

        ServletHandler servletHandler = servletContextHandler.getServletHandler();
        String voJobservletName = V0JobsServlet.class.getSimpleName();
        servletHandler.addServlet(new ServletHolder(voJobservletName, new V0JobsServlet()));
        ServletMapping jobServletMappingV0 = new ServletMapping();
        jobServletMappingV0.setPathSpec("/v0/jobs");
        jobServletMappingV0.setServletName(voJobservletName);

        ServletMapping jobServletMappingV1 = new ServletMapping();
        jobServletMappingV1.setPathSpec("/v1/jobs");
        jobServletMappingV1.setServletName(voJobservletName);

        ServletMapping jobServletMappingV2 = new ServletMapping();
        jobServletMappingV2.setPathSpec("/v2/jobs");
        jobServletMappingV2.setServletName(voJobservletName);

        servletHandler.addServletMapping(jobServletMappingV0);
        servletHandler.addServletMapping(jobServletMappingV1);
        servletHandler.addServletMapping(jobServletMappingV2);

        mapServlet(V0JobServlet.class, "/v0/job/*");
        mapServlet(V1JobServlet.class, "/v1/job/*");
        mapServlet(V2JobServlet.class, "/v2/job/*");
        mapServlet(SLAServlet.class, "/v1/sla/*");
        mapServlet(V2SLAServlet.class, "/v2/sla/*");
        mapServlet(V2ValidateServlet.class, "/v2/validate/*");
    }

    private void mapServlet(final Class<? extends Servlet> servletClass, final String servletPath) {
        try {
            servletContextHandler.addServlet(new ServletHolder(servletClass.newInstance()), servletPath);
        } catch (final InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }
}
