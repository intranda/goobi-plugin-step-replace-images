package org.goobi.api.rest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.intranda.goobi.plugins.replace_images.model.GoobiImage;
import de.intranda.goobi.plugins.replace_images.model.ImageNature;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.Process;
import ugh.dl.*;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;
import ugh.fileformats.slimjson.SlimDigitalDocument;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Log4j2
@Path("/plugins/replaceimages")
public class ReplaceImagesApi {
    // TODO: Duplicate of `module-base` field
    public static String TITLE = "intranda_step_replace-images";

    private static Gson gson = new Gson();
    private static Type arrayListType = TypeToken.getParameterized(ArrayList.class, GoobiImage.class).getType();
    private static XMLConfiguration pluginConfig;

//    public static Route prefsForProcess = (req, res) -> {
//        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
//        DocStruct topDs = p.readMetadataFile().getDigitalDocument().getLogicalDocStruct();
//        if (topDs.getType().isAnchor()) {
//            topDs = topDs.getAllChildren().get(0);
//        }
//        String rulesetFile = ConfigurationHelper.getInstance().getRulesetFolder() + "/" + p.getRegelsatz().getDatei();
//
//        res.header("content-type", "application-xml");
//        OutputStream os = res.raw().getOutputStream();
//        Files.copy(Paths.get(rulesetFile), os);
//        return "";
//    };


    @GET
    @Path("/process/{processid}/{stepid}/images")
    public List<GoobiImage> allImages(@PathParam("processid") int processid, @PathParam("stepid") int stepid) throws ReadException, SwapException, IOException, PreferencesException {
        Process p = ProcessManager.getProcessById(processid);
        String stepTitle = StepManager.getStepById(stepid).getTitel();

        SubnodeConfiguration config = getProjectAndStepConfig(p.getProjekt().getTitel(), stepTitle);
        List<String> imageNatureNames = Arrays.asList(config.getStringArray("imageFolder"));
        List<Map<String, String>> imageNatureBasenameToNameMap = imageNatureNames.stream()
                .sorted()
                .map(folder -> createBasenameToNameMap(p, folder))
                .toList();

        DocStruct physDs = p.readMetadataFile().getDigitalDocument().getPhysicalDocStruct();

        Prefs prefs = p.getRegelsatz().getPreferences();

        MetadataType remarkType = prefs.getMetadataTypeByName("Remark");
        MetadataType idType = prefs.getMetadataTypeByName("ImageIdentifier");
        MetadataType orderLabelType = prefs.getMetadataTypeByName("logicalPageNumber");

        List<GoobiImage> images = new ArrayList<>();
        if (physDs.getAllChildren() != null) {
            for (DocStruct ds : physDs.getAllChildren()) {
                String remark = "";
                List<Metadata> remarkMeta = (List<Metadata>) ds.getAllMetadataByType(remarkType);
                if (remarkMeta != null && !remarkMeta.isEmpty()) {
                    remark = remarkMeta.get(0).getValue();
                }
                String orderLabel = "";
                List<Metadata> orderLabelMeta = (List<Metadata>) ds.getAllMetadataByType(orderLabelType);
                if (orderLabelMeta != null && !orderLabelMeta.isEmpty()) {
                    orderLabel = orderLabelMeta.get(0).getValue();
                }
                List<Metadata> idMeta = (List<Metadata>) ds.getAllMetadataByType(idType);
                String id = "";
                if (idMeta != null && !idMeta.isEmpty()) {
                    id = idMeta.get(0).getValue();
                } else {
                    id = ds.getAllMetadataByType(prefs.getMetadataTypeByName("physPageNumber")).get(0).getValue();
                }
                String metsImagename = ds.getImageName();
                String basename = metsImagename.substring(0, metsImagename.lastIndexOf('.'));

                List<ImageNature> imageNatures = new ArrayList<>();
                for (int i = 0; i < imageNatureNames.size(); i++) {
                    imageNatures.add(new ImageNature(imageNatureNames.get(i),
                            imageNatureBasenameToNameMap.get(i).get(basename)));
                }

                images.add(new GoobiImage(basename, id, remark, orderLabel, imageNatures));
            }
        }

        return images;
    }

    public static Map<String, String> createBasenameToNameMap(Process p, String folder) {
        Map<String, String> basenameToName = new HashMap<>();
        java.nio.file.Path imagesPath = null;
        try {
            imagesPath = Paths.get(p.getConfiguredImageFolder(folder));
        } catch (IOException | SwapException | DAOException e) {
            log.error(e);
        }
        if (imagesPath == null) {
            return basenameToName;
        }
        try (DirectoryStream<java.nio.file.Path> dirStream = Files.newDirectoryStream(imagesPath)) {
            for (java.nio.file.Path imagePath : dirStream) {
                String imageName = imagePath.getFileName().toString();
                String basename = imageName.substring(0, imageName.lastIndexOf('.'));
                basenameToName.put(basename, imageName);
            }
        } catch (IOException e) {
            log.error(e);
        }
        return basenameToName;
    }

    @DELETE
    @Path("/process/{processid}/images/{folder}/{name}")
    public Response deleteImage(@PathParam("processid") int processid, @PathParam("folder") String folder, @PathParam("name") String name) throws DAOException, SwapException, IOException {
        Process p = ProcessManager.getProcessById(processid);
        java.nio.file.Path imageDir = Paths.get(p.getConfiguredImageFolder(folder));
        Files.delete(resolveSanitized(imageDir, name));
        return Response.ok().build();
    }

    @PUT
    @Path("/process/{processid}/images")
    public Response updateImages(@PathParam("processid") int processid, String body) throws ReadException, SwapException, IOException, PreferencesException, WriteException {
        Process p = ProcessManager.getProcessById(processid);
        List<GoobiImage> newimages = gson.fromJson(body, arrayListType);
        Map<String, GoobiImage> idMap = new HashMap<>();
        for (GoobiImage im : newimages) {
            idMap.put(im.getId(), im);
        }

        Fileformat ff = p.readMetadataFile();
        DocStruct physDs = ff.getDigitalDocument().getPhysicalDocStruct();

        Prefs prefs = p.getRegelsatz().getPreferences();

        MetadataType idType = prefs.getMetadataTypeByName("ImageIdentifier");
        MetadataType physNumType = prefs.getMetadataTypeByName("physPageNumber");

        for (DocStruct ds : physDs.getAllChildren()) {
            List<Metadata> idList = (List<Metadata>) ds.getAllMetadataByType(idType);
            if (idList == null || idList.isEmpty()) {
                idList = (List<Metadata>) ds.getAllMetadataByType(physNumType);
            }
            String currId = idList.get(0).getValue();
            GoobiImage im = idMap.get(currId);
            if (im != null) {
                String oldImagename = ds.getImageName();
                Files.deleteIfExists(Paths.get(oldImagename));
                ds.setImageName(im.getName());
            }
        }
        p.writeMetadataFile(ff);
        return Response.ok().build();
    }

    @POST
    @Path("/process/{processid}/saveMets")
    public Response saveMets(@PathParam("processid") int processid, SlimDigitalDocument slimDD) throws WriteException, SwapException, IOException, PreferencesException {
        //        ObjectMapper mapper = new ObjectMapper();
//        SlimDigitalDocument slimDD = gson.fromJson(req.body(), SlimDigitalDocument.class);
        DigitalDocument dd = slimDD.toDigitalDocument();
        Process p = ProcessManager.getProcessById(processid);
        Fileformat ff = new MetsMods();
        ff.setDigitalDocument(dd);

        p.writeMetadataFile(ff);
        return Response.ok().build();
    }

    public static java.nio.file.Path resolveSanitized(java.nio.file.Path root, String toResolve) {
        java.nio.file.Path relative = root.resolve(toResolve).normalize();
        if (!relative.startsWith(root)) {
            throw new IllegalArgumentException("Path traverses");
        }
        return relative;
    }

    public static SubnodeConfiguration getProjectAndStepConfig(String projectName, String stepTitle) {
        if (pluginConfig == null) {
            pluginConfig = ConfigPlugins.getPluginConfig(TITLE);
            pluginConfig.setExpressionEngine(new XPathExpressionEngine());
            pluginConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        }

        SubnodeConfiguration projectAndStepConfig = null;
        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            projectAndStepConfig = pluginConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + stepTitle + "']");
        } catch (IllegalArgumentException e) {
            try {
                projectAndStepConfig = pluginConfig.configurationAt("//config[./project = '*'][./step = '" + stepTitle + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    projectAndStepConfig = pluginConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    projectAndStepConfig = pluginConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }
        return projectAndStepConfig;
    }
}
