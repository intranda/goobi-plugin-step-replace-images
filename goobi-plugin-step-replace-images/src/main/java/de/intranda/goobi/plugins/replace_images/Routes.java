package de.intranda.goobi.plugins.replace_images;

import com.google.gson.Gson;

import spark.Service;

public class Routes {
    private static Gson gson = new Gson();

    public static void initRoutes(Service http) {
        http.path("/replaceimages", () -> {
            http.get("/process/:processid/images", Handlers.allImages, gson::toJson);
            http.delete("/process/:processid/images/:name", Handlers.deleteImage, gson::toJson);
            http.post("/process/:processid/saveMets", Handlers.saveMets);
        });
    }
}
