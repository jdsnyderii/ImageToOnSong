package com.imagetoonsong.config;

import java.util.HashMap;
import java.util.Map;

public class TessConfig {

    private String name;
    private Integer psm;
    private Integer oem;
    private String whitelist;
    private String blacklist;
    private Map<String, String> variables = new HashMap<>();

    // Getters & Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPsm() {
        return psm;
    }

    public void setPsm(Integer psm) {
        this.psm = psm;
    }

    public Integer getOem() {
        return oem;
    }

    public void setOem(Integer oem) {
        this.oem = oem;
    }

    public String getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(String whitelist) {
        this.whitelist = whitelist;
    }

    public String getBlacklist() {
        return blacklist;
    }

    public void setBlacklist(String blacklist) {
        this.blacklist = blacklist;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables != null ? variables : new HashMap<>();
    }

    // Convenience method
    public void addVariable(String key, String value) {
        variables.put(key, value);
    }
}