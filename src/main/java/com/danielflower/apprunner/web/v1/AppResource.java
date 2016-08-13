package com.danielflower.apprunner.web.v1;

import com.danielflower.apprunner.AppEstate;
import com.danielflower.apprunner.io.OutputToWriterBridge;
import com.danielflower.apprunner.mgmt.AppDescription;
import com.danielflower.apprunner.mgmt.AppManager;
import com.danielflower.apprunner.mgmt.Availability;
import com.danielflower.apprunner.problems.AppNotFoundException;
import com.danielflower.apprunner.runners.UnsupportedProjectTypeException;
import io.swagger.annotations.*;
import org.apache.commons.io.output.StringBuilderWriter;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Api(value = "Application")
@Path("/apps")
public class AppResource {
    public static final Logger log = LoggerFactory.getLogger(AppResource.class);

    private final AppEstate estate;

    public AppResource(AppEstate estate) {
        this.estate = estate;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets all registered apps")
    public String apps(@Context UriInfo uriInfo) {

        JSONObject result = new JSONObject();
        List<JSONObject> apps = new ArrayList<>();
        estate.all()
            .sorted((o1, o2) -> o1.name().compareTo(o2.name()))
            .forEach(d -> apps.add(
                appJson(uriInfo.getRequestUri(), d)));
        result.put("apps", apps);
        return result.toString(4);
    }

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets a single app")
    public Response app(@Context UriInfo uriInfo, @ApiParam(required = true, example = "app-runner-home") @PathParam("name") String name) {
        Optional<AppDescription> app = estate.app(name);
        if (app.isPresent()) {
            return Response.ok(appJson(uriInfo.getRequestUri(), app.get()).toString(4)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/{name}/build.log")
    @ApiOperation(value = "Gets the latest build log as plain text for the given app")
    public String buildLogs(@ApiParam(required = true, example = "app-runner-home") @PathParam("name") String name) {
        Optional<AppDescription> namedApp = estate.app(name);
        if (namedApp.isPresent())
            return namedApp.get().latestBuildLog();
        throw new AppNotFoundException("No app found with name '" + name + "'. Valid names: " + estate.allAppNames());
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/{name}/console.log")
    @ApiOperation(value = "Gets the latest console log as plain text for the given app")
    public String consoleLogs(@ApiParam(required = true, example = "app-runner-home") @PathParam("name") String name) {
        Optional<AppDescription> namedApp = estate.app(name);
        if (namedApp.isPresent())
            return namedApp.get().latestConsoleLog();
        throw new AppNotFoundException("No app found with name '" + name + "'. Valid names: " + estate.allAppNames());
    }

    private static JSONObject appJson(URI uri, AppDescription app) {
        URI restURI = uri.resolve("/api/v1/");

        Availability availability = app.currentAvailability();
        return new JSONObject()
            .put("name", app.name())
            .put("contributors", getContributorsList(app))
            .put("buildLogUrl", appUrl(app, restURI, "build.log"))
            .put("consoleLogUrl", appUrl(app, restURI, "console.log"))
            .put("url", uri.resolve("/" + app.name() + "/"))
            .put("deployUrl", appUrl(app, restURI, "deploy"))
            .put("available", availability.isAvailable)
            .put("availableStatus", availability.availabilityStatus)
            .put("gitUrl", app.gitUrl());
    }

    private static String getContributorsList(AppDescription app) {
        String contributors = "";
        String[] contributorsArray = app.contributors().toArray(new String[0]);
        Arrays.sort(contributorsArray);
        for (String name : contributorsArray) {
            contributors += name + ", ";
        }
        if (contributors.length() > 2) {
            contributors = contributors.substring(0, contributors.length() - 2);
        }
        return contributors;
    }

    private static URI appUrl(AppDescription app, URI restURI, String path) {
        return restURI.resolve("apps/" + app.name() + "/" + path);
    }

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Registers an app or updates an existing app")
    @ApiResponses(value = {
        @ApiResponse(code = 201, message = "The new app was successfully registered"),
        @ApiResponse(code = 200, message = "The existing app was updated"),
        @ApiResponse(code = 400, message = "The git URL was not specified or the git repo could not be cloned"),
        @ApiResponse(code = 501, message = "The app type is not supported by this apprunner")
    })
    public Response create(@Context UriInfo uriInfo,
                           @ApiParam(required = true, example = "https://github.com/danielflower/app-runner-home.git", value = "An SSH or HTTP git URL that points to an app-runner compatible app")
                           @FormParam("gitUrl") String gitUrl,
                           @ApiParam(example = "app-runner-home", value = "The ID that the app will be referenced which should just be letters, numbers, and hyphens. Leave blank to infer it from the git URL")
                           @FormParam("appName") String appName) {
        log.info("Received request to create " + gitUrl);
        if (isBlank(gitUrl)) {
            return Response.status(400).entity(new JSONObject()
                .put("message", "No git URL was specified")
                .toString()).build();
        }

        try {
            appName = isBlank(appName) ? AppManager.nameFromUrl(gitUrl) : appName;

            AppDescription appDescription;
            int status;
            Optional<AppDescription> existing = estate.app(appName);
            if (existing.isPresent()) {
                appDescription = existing.get();
                estate.remove(appDescription);
                status = 200;
            } else {
                status = 201;
            }
            try {
                appDescription = estate.addApp(gitUrl, appName);
            } catch (UnsupportedProjectTypeException e) {
                return Response.status(501)
                    .entity(new JSONObject()
                        .put("message", "No suitable runner found for this app")
                        .put("gitUrl", gitUrl)
                        .toString())
                    .build();
            } catch (GitAPIException e) {
                return Response.status(400)
                    .entity(new JSONObject()
                        .put("message", "Could not clone git repository: " + e.getMessage())
                        .put("gitUrl", gitUrl)
                        .toString())
                    .build();
            }

            return Response.status(status)
                .header("Location", uriInfo.getRequestUri() + "/" + appDescription.name())
                .entity(appJson(uriInfo.getRequestUri(), estate.app(appName).get()).toString(4))
                .build();
        } catch (Exception e) {
            log.error("Error while adding app", e);
            return Response.serverError().entity("Error while adding app: " + e.getMessage()).build();
        }
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{name}")
    @ApiOperation(value = "De-registers an application")
    public Response delete(@Context UriInfo uriInfo, @ApiParam(required=true) @PathParam("name") String name) throws IOException {
        Optional<AppDescription> existing = estate.app(name);
        if (existing.isPresent()) {
            AppDescription appDescription = existing.get();
            String entity = appJson(uriInfo.getRequestUri(), appDescription).toString(4);
            estate.remove(appDescription);
            return Response.ok(entity).build();
        } else {
            return Response.status(400).entity("Could not find app with name " + name).build();
        }
    }

    @POST /* Maybe should be PUT, but too many hooks only use POST */
    @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
    @Path("/{name}/deploy")
    @ApiOperation(value = "Deploys an app", notes = "Deploys the app by fetching the latest changes from git, building it, " +
        "starting it, polling for successful startup by making GET requests to /{name}/, and if it returns any HTTP response " +
        "it shuts down the old version of the app. If any steps before that fail, the old version of the app will continue serving " +
        "requests.")
    @ApiResponses(value = {@ApiResponse(code = 200, message = "Returns 200 if the command was received successfully. Whether the build " +
        "actually succeeds or fails is ignored. Returns streamed plain text of the build log and console startup, unless the Accept" +
        " header includes 'application/json'.")})
    public Response deploy(@Context UriInfo uriInfo, @ApiParam(example = "application/json") @HeaderParam("Accept") String accept,
                           @ApiParam(required = true, example = "app-runner-home") @PathParam("name") String name) throws IOException {
        StreamingOutput stream = new UpdateStreamer(name);
        if (MediaType.APPLICATION_JSON.equals(accept)) {
            StringBuilderWriter output = new StringBuilderWriter();
            try (WriterOutputStream writer = new WriterOutputStream(output)) {
                stream.write(writer);
                return app(uriInfo, name);
            }
        } else {
            return Response.ok(stream).build();
        }
    }

    private class UpdateStreamer implements StreamingOutput {
        private final String name;

        UpdateStreamer(String name) {
            this.name = name;
        }

        public void write(OutputStream output) throws IOException, WebApplicationException {
            try (Writer writer = new OutputStreamWriter(output)) {
                writer.write("Going to build and deploy " + name + " at " + new Date() + "\n");
                writer.flush();
                log.info("Going to update " + name);
                try {
                    estate.update(name, new OutputToWriterBridge(writer));
                    log.info("Finished updating " + name);
                    writer.write("Success\n");
                } catch (AppNotFoundException e) {
                    Response r = Response.status(404).entity(e.getMessage()).build();
                    throw new WebApplicationException(r);
                } catch (Exception e) {
                    log.error("Error while updating " + name, e);
                    writer.write("Error while updating: " + e);
                    if (e instanceof IOException) {
                        throw (IOException) e;
                    }
                }
            }
        }
    }

    @PUT
    @Path("/{name}/stop")
    @ApiOperation(value = "Stop an app from running, but does not de-register it. Call the deploy action to restart it.")
    public Response stop(@ApiParam(required = true) @PathParam("name") String name) {
        Optional<AppDescription> app = estate.app(name);
        if (app.isPresent()) {
            try {
                log.info("Going to stop " + name);
                app.get().stopApp();
                return Response.ok().build();
            } catch (Exception e) {
                log.error("Couldn't stop app via REST call", e);
                return Response.serverError().entity(e.toString()).build();
            }
        } else {
            return Response.status(404).build();
        }
    }

}
