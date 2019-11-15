package de.intranda.goobi.plugins.replace_images.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GoobiImage {
    private String name;
    private String folder;

    private String id;
    private String remark;
}
