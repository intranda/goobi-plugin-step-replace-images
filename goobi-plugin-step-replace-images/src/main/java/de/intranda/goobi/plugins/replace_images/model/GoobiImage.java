package de.intranda.goobi.plugins.replace_images.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GoobiImage {
    private String name;

    private String id;
    private String remark;

    private List<ImageNature> natures;
}
