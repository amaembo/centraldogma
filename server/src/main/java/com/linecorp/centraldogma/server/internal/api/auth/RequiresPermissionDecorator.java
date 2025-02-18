/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.api.auth;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.centraldogma.server.internal.api.auth.RequiresRoleDecorator.handleException;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.Decorator;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.centraldogma.server.internal.admin.auth.AuthUtil;
import com.linecorp.centraldogma.server.internal.api.HttpApiUtil;
import com.linecorp.centraldogma.server.metadata.MetadataService;
import com.linecorp.centraldogma.server.metadata.MetadataServiceInjector;
import com.linecorp.centraldogma.server.metadata.Permission;
import com.linecorp.centraldogma.server.metadata.ProjectRole;
import com.linecorp.centraldogma.server.metadata.User;
import com.linecorp.centraldogma.server.storage.project.Project;

/**
 * A {@link Decorator} to allow a request from a user who has the specified {@link Permission}.
 */
public final class RequiresPermissionDecorator extends SimpleDecoratingHttpService {

    private final Permission requiredPermission;

    RequiresPermissionDecorator(HttpService delegate, Permission requiredPermission) {
        super(delegate);
        this.requiredPermission = requireNonNull(requiredPermission, "requiredPermission");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final MetadataService mds = MetadataServiceInjector.getMetadataService(ctx);
        final User user = AuthUtil.currentUser(ctx);

        final String projectName = ctx.pathParam("projectName");
        checkArgument(!isNullOrEmpty(projectName), "no project name is specified");
        final String repoName = ctx.pathParam("repoName");
        checkArgument(!isNullOrEmpty(repoName), "no repository name is specified");

        if (Project.isReservedRepoName(repoName)) {
            return serveInternalRepo(ctx, req, mds, user, projectName, repoName);
        } else {
            return serveUserRepo(ctx, req, mds, user, projectName, repoName);
        }
    }

    private HttpResponse serveInternalRepo(ServiceRequestContext ctx, HttpRequest req,
                                           MetadataService mds, User user,
                                           String projectName, String repoName) throws Exception {
        if (user.isAdmin()) {
            return unwrap().serve(ctx, req);
        }
        if (Project.REPO_DOGMA.equals(repoName)) {
            return throwForbiddenResponse(ctx, projectName, repoName, "administrator");
        }
        assert Project.REPO_META.equals(repoName);

        return HttpResponse.from(mds.findRole(projectName, user).handle((role, cause) -> {
            if (cause != null) {
                return handleException(ctx, cause);
            }
            if (role != ProjectRole.OWNER) {
                return throwForbiddenResponse(ctx, projectName, repoName, "owner");
            }
            try {
                return unwrap().serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    private static HttpResponse throwForbiddenResponse(ServiceRequestContext ctx, String projectName,
                                                       String repoName, String adminOrOwner) {
        return HttpApiUtil.throwResponse(ctx, HttpStatus.FORBIDDEN,
                                         "Repository '%s/%s' can be accessed only by an %s.",
                                         projectName, repoName, adminOrOwner);
    }

    private HttpResponse serveUserRepo(ServiceRequestContext ctx, HttpRequest req,
                                       MetadataService mds, User user,
                                       String projectName, String repoName) throws Exception {
        final CompletionStage<Collection<Permission>> f;
        try {
            f = mds.findPermissions(projectName, repoName, user);
        } catch (Throwable cause) {
            return handleException(ctx, cause);
        }

        return HttpResponse.from(f.handle((permission, cause) -> {
            if (cause != null) {
                return handleException(ctx, cause);
            }
            if (!permission.contains(requiredPermission)) {
                return HttpApiUtil.throwResponse(
                        ctx, HttpStatus.FORBIDDEN,
                        "You must have %s permission for repository '%s/%s'.",
                        requiredPermission, projectName, repoName);
            }
            try {
                return unwrap().serve(ctx, req);
            } catch (Exception e) {
                return Exceptions.throwUnsafely(e);
            }
        }));
    }

    /**
     * A {@link DecoratorFactoryFunction} which creates a {@link RequiresPermissionDecorator} with a read
     * {@link Permission}.
     */
    public static final class RequiresReadPermissionDecoratorFactory
            implements DecoratorFactoryFunction<RequiresReadPermission> {
        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(RequiresReadPermission parameter) {
            return delegate -> new RequiresPermissionDecorator(delegate, Permission.READ);
        }
    }

    /**
     * A {@link DecoratorFactoryFunction} which creates a {@link RequiresPermissionDecorator} with a write
     * {@link Permission}.
     */
    public static final class RequiresWritePermissionDecoratorFactory
            implements DecoratorFactoryFunction<RequiresWritePermission> {
        @Override
        public Function<? super HttpService, ? extends HttpService>
        newDecorator(RequiresWritePermission parameter) {
            return delegate -> new RequiresPermissionDecorator(delegate, Permission.WRITE);
        }
    }
}
