package de.intranda.goobi.plugins.replace_images;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.goobi.beans.Process;

import com.google.gson.Gson;

import de.intranda.goobi.plugins.replace_images.model.GoobiImage;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.persistence.managers.ProcessManager;
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
    private static String title = "intranda_step_structure-data-editor";
    private static Gson gson = new Gson();

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

        DocStruct physDs = p.readMetadataFile().getDigitalDocument().getPhysicalDocStruct();

        Prefs prefs = p.getRegelsatz().getPreferences();

        MetadataType remarkType = prefs.getMetadataTypeByName("Remark");
        MetadataType idType = prefs.getMetadataTypeByName("ImageIdentifier");

        List<GoobiImage> images = new ArrayList<>();
        for (DocStruct ds : physDs.getAllChildren()) {
            String remark = "";
            List<Metadata> remarkMeta = (List<Metadata>) ds.getAllMetadataByType(remarkType);
            if (remarkMeta != null && !remarkMeta.isEmpty()) {
                remark = remarkMeta.get(0).getValue();
            }
            String id = ds.getAllMetadataByType(idType).get(0).getValue();

            images.add(new GoobiImage(ds.getImageName(), "master", id, remark));
        }

        //        XMLConfiguration conf = ConfigPlugins.getPluginConfig(title);
        res.header("content-type", "application/json");
        return images;
    };

    public static Route deleteImage = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        Path imageDir = Paths.get(p.getImagesOrigDirectory(false));
        Files.delete(imageDir.resolve(req.params("name")));
        return "";
    };

    public static Route updateImage = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        String id = req.params("id");
        GoobiImage newimage = gson.fromJson(req.body(), GoobiImage.class);

        DocStruct physDs = p.readMetadataFile().getDigitalDocument().getPhysicalDocStruct();

        Prefs prefs = p.getRegelsatz().getPreferences();

        MetadataType idType = prefs.getMetadataTypeByName("ImageIdentifier");
        for (DocStruct ds : physDs.getAllChildren()) {
            String currId = ds.getAllMetadataByType(idType).get(0).getValue();
            if (currId.equals(id)) {
                ds.setImageName(newimage.getName());
            }
        }
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

    public static Route save = (req, res) -> {
        Process p = ProcessManager.getProcessById(Integer.parseInt(req.params("processid")));
        StorageProviderInterface sp = StorageProvider.getInstance();
        Path pluginDir = Paths.get(p.getProcessDataDirectory());
        try (InputStream is = new ByteArrayInputStream(req.bodyAsBytes())) {
            sp.uploadFile(is, pluginDir.resolve("meta.json"));
        }
        return "";
    };
}
