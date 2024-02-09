package de.intranda.goobi.plugins.replace_images;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.Process;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import de.intranda.goobi.plugins.ReplaceImages;
import de.intranda.goobi.plugins.replace_images.model.GoobiImage;
import de.intranda.goobi.plugins.replace_images.model.ImageNature;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.extern.log4j.Log4j;
import spark.Route;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;
import ugh.fileformats.slimjson.SlimDigitalDocument;

@Log4j
public class Handlers {
    private static Gson gson = new Gson();
    private static Type arrayListType = TypeToken.getParameterized(ArrayList.class, GoobiImage.class).getType();
    private static XMLConfiguration pluginConfig;

    public static Route prefsForProcess = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        DocStruct topDs = p.readMetadataFile().getDigitalDocument().getLogicalDocStruct();
        if (topDs.getType().isAnchor()) {
            topDs = topDs.getAllChildren().get(0);
        }
        String rulesetFile = ConfigurationHelper.getInstance().getRulesetFolder() + "/" + p.getRegelsatz().getDatei();

        res.header("content-type", "application-xml");
        OutputStream os = res.raw().getOutputStream();
        Files.copy(Paths.get(rulesetFile), os);
        return "";
    };

    @SuppressWarnings("unchecked")
    public static Route allImages = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        int stepId = Integer.parseInt(req.params("stepId"));
        String stepTitle = StepManager.getStepById(stepId).getTitel();

        SubnodeConfiguration config = getProjectAndStepConfig(p.getProjekt().getTitel(), stepTitle);
        List<String> imageNatureNames = Arrays.asList(config.getStringArray("imageFolder"));
        List<Map<String, String>> imageNatureBasenameToNameMap = imageNatureNames.stream()
                .sorted()
                .map(folder -> createBasenameToNameMap(p, folder))
                .collect(Collectors.toList());

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

        //        XMLConfiguration conf = ConfigPlugins.getPluginConfig(title);
        res.header("content-type", "application/json");
        return images;
    };

    public static Map<String, String> createBasenameToNameMap(Process p, String folder) {
        Map<String, String> basenameToName = new HashMap<>();
        Path imagesPath = null;
        try {
            imagesPath = Paths.get(p.getConfiguredImageFolder(folder));
        } catch (IOException | SwapException | DAOException e) {
            log.error(e);
        }
        if (imagesPath == null) {
            return basenameToName;
        }
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(imagesPath)) {
            for (Path imagePath : dirStream) {
                String imageName = imagePath.getFileName().toString();
                String basename = imageName.substring(0, imageName.lastIndexOf('.'));
                basenameToName.put(basename, imageName);
            }
        } catch (IOException e) {
            log.error(e);
        }
        return basenameToName;
    }

    public static Route deleteImage = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        String folder = req.params("folder");
        Path imageDir = Paths.get(p.getConfiguredImageFolder(folder));
        Files.delete(resolveSanitized(imageDir, req.params("name")));
        return "";
    };

    @SuppressWarnings("unchecked")
    public static Route updateImages = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        List<GoobiImage> newimages = gson.fromJson(req.body(), arrayListType);
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
        return "";
    };

    public static Route saveMets = (req, res) -> {
        //        ObjectMapper mapper = new ObjectMapper();
        SlimDigitalDocument slimDD = gson.fromJson(req.body(), SlimDigitalDocument.class);
        DigitalDocument dd = slimDD.toDigitalDocument();
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        Fileformat ff = new MetsMods();
        ff.setDigitalDocument(dd);

        p.writeMetadataFile(ff);
        return "";
    };

    public static Path resolveSanitized(Path root, String toResolve) {
        Path relative = root.resolve(toResolve).normalize();
        if (!relative.startsWith(root)) {
            throw new IllegalArgumentException("Path traverses");
        }
        return relative;
    }

    public static SubnodeConfiguration getProjectAndStepConfig(String projectName, String stepTitle) {
        if (pluginConfig == null) {
            pluginConfig = ConfigPlugins.getPluginConfig(ReplaceImages.TITLE);
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
