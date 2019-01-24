/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.naming.controllers;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.api.naming.CommonParams;
import com.alibaba.nacos.api.selector.SelectorType;
import com.alibaba.nacos.core.utils.WebUtils;
import com.alibaba.nacos.naming.cluster.ServerListManager;
import com.alibaba.nacos.naming.core.DistroMapper;
import com.alibaba.nacos.naming.core.Instance;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.core.ServiceManager;
import com.alibaba.nacos.naming.exception.NacosException;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.selector.LabelSelector;
import com.alibaba.nacos.naming.selector.NoneSelector;
import com.alibaba.nacos.naming.selector.Selector;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * Service operation controller
 *
 * @author nkorange
 */
@RestController
@RequestMapping(UtilsAndCommons.NACOS_NAMING_CONTEXT + "/service")
public class ServiceController {

    @Autowired
    protected ServiceManager serviceManager;

    @Autowired
    private SwitchDomain switchDomain;

    @Autowired
    private DistroMapper distroMapper;

    @Autowired
    private ServerListManager serverListManager;

    @RequestMapping(value = "", method = RequestMethod.POST)
    public String create(HttpServletRequest request) throws Exception {

        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());

        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);

        if (serviceManager.getService(namespaceId, serviceName) != null) {
            throw new IllegalArgumentException("specified service already exists, serviceName : " + serviceName);
        }

        float protectThreshold = NumberUtils.toFloat(WebUtils.optional(request, "protectThreshold", "0"));
        String healthCheckMode = WebUtils.optional(request, "healthCheckMode", switchDomain.defaultHealthCheckMode);
        String metadata = WebUtils.optional(request, "metadata", StringUtils.EMPTY);
        String selector = WebUtils.optional(request, "selector", StringUtils.EMPTY);
        Map<String, String> metadataMap = new HashMap<>(16);
        if (StringUtils.isNotBlank(metadata)) {
            metadataMap = UtilsAndCommons.parseMetadata(metadata);
        }

        Service domObj = new Service();
        domObj.setName(serviceName);
        domObj.setProtectThreshold(protectThreshold);
        domObj.setHealthCheckMode(healthCheckMode.toLowerCase());
        domObj.setEnabled(true);
        domObj.setMetadata(metadataMap);
        domObj.setSelector(parseSelector(selector));
        domObj.setNamespaceId(namespaceId);

        // now valid the dom. if failed, exception will be thrown
        domObj.setLastModifiedMillis(System.currentTimeMillis());
        domObj.recalculateChecksum();
        domObj.valid();

        serviceManager.addOrReplaceService(domObj);

        return "ok";
    }

    @RequestMapping(value = "", method = RequestMethod.DELETE)
    public String remove(HttpServletRequest request) throws Exception {

        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);

        Service service = serviceManager.getService(namespaceId, serviceName);
        if (service == null) {
            throw new IllegalArgumentException("specified service not exist, serviceName : " + serviceName);
        }

        if (!service.allIPs().isEmpty()) {
            throw new IllegalArgumentException("specified service has instances, serviceName : " + serviceName);
        }

        serviceManager.easyRemoveDom(namespaceId, serviceName);

        return "ok";
    }

    @RequestMapping(value = "", method = RequestMethod.GET)
    public JSONObject detail(HttpServletRequest request) throws Exception {

        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);

        Service domain = serviceManager.getService(namespaceId, serviceName);
        if (domain == null) {
            throw new NacosException(NacosException.NOT_FOUND, "serivce " + serviceName + " is not found!");
        }

        JSONObject res = new JSONObject();
        res.put("name", serviceName);
        res.put("namespaceId", domain.getNamespaceId());
        res.put("protectThreshold", domain.getProtectThreshold());
        res.put("healthCheckMode", domain.getHealthCheckMode());
        res.put("metadata", domain.getMetadata());
        res.put("selector", domain.getSelector());

        return res;
    }

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public JSONObject list(HttpServletRequest request) throws Exception {

        int pageNo = NumberUtils.toInt(WebUtils.required(request, "pageNo"));
        int pageSize = NumberUtils.toInt(WebUtils.required(request, "pageSize"));
        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());
        String selectorString = WebUtils.optional(request, "selector", StringUtils.EMPTY);

        List<String> doms = serviceManager.getAllDomNamesList(namespaceId);

        if (doms == null || doms.isEmpty()) {
            throw new NacosException(NacosException.INVALID_PARAM, "No service exist in " + namespaceId);
        }

        if (StringUtils.isNotBlank(selectorString)) {

            JSONObject selectorJson = JSON.parseObject(selectorString);
            switch (SelectorType.valueOf(selectorJson.getString("type"))) {
                case label:
                    String expression = selectorJson.getString("expression");
                    if (StringUtils.isBlank(expression)) {
                        break;
                    }
                    expression = StringUtils.deleteWhitespace(expression);
                    // Now we only support the following expression:
                    // INSTANCE.metadata.xxx = 'yyy' or
                    // SERVICE.metadata.xxx = 'yyy'
                    String[] terms = expression.split("=");
                    String[] factors = terms[0].split("\\.");
                    switch (factors[0]) {
                        case "INSTANCE":
                            doms = filterInstanceMetadata(namespaceId, doms, factors[factors.length - 1], terms[1].replace("'", ""));
                            break;
                        case "SERVICE":
                            doms = filterServiceMetadata(namespaceId, doms, factors[factors.length - 1], terms[1].replace("'", ""));
                            break;
                        default:
                            break;
                    }
                    break;
                default:
                    break;
            }
        }

        int start = (pageNo - 1) * pageSize;
        int end = start + pageSize;

        if (start < 0) {
            start = 0;
        }

        if (end > doms.size()) {
            end = doms.size();
        }

        JSONObject result = new JSONObject();

        result.put("doms", doms.subList(start, end));
        result.put("count", doms.size());

        return result;

    }

    @RequestMapping(value = "", method = RequestMethod.PUT)
    public String update(HttpServletRequest request) throws Exception {

        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());
        String serviceName = WebUtils.required(request, CommonParams.SERVICE_NAME);
        float protectThreshold = NumberUtils.toFloat(WebUtils.required(request, "protectThreshold"));
        String healthCheckMode = WebUtils.required(request, "healthCheckMode");
        String metadata = WebUtils.optional(request, "metadata", StringUtils.EMPTY);
        String selector = WebUtils.optional(request, "selector", StringUtils.EMPTY);

        Service domain = serviceManager.getService(namespaceId, serviceName);
        if (domain == null) {
            throw new NacosException(NacosException.INVALID_PARAM, "service " + serviceName + " not found!");
        }

        domain.setProtectThreshold(protectThreshold);

        domain.setHealthCheckMode(healthCheckMode);

        Map<String, String> metadataMap = UtilsAndCommons.parseMetadata(metadata);
        domain.setMetadata(metadataMap);

        domain.setSelector(parseSelector(selector));

        domain.setLastModifiedMillis(System.currentTimeMillis());
        domain.recalculateChecksum();
        domain.valid();

        serviceManager.addOrReplaceService(domain);

        return "ok";
    }

    @RequestMapping("/allDomNames")
    public JSONObject allDomNames(HttpServletRequest request) throws Exception {

        boolean responsibleOnly = Boolean.parseBoolean(WebUtils.optional(request, "responsibleOnly", "false"));

        Map<String, Set<String>> doms = new HashMap<>(16);

        Map<String, Set<String>> domMap = serviceManager.getAllDomNames();

        for (String namespaceId : domMap.keySet()) {
            doms.put(namespaceId, new HashSet<>());
            for (String dom : domMap.get(namespaceId)) {
                if (distroMapper.responsible(dom) || !responsibleOnly) {
                    doms.get(namespaceId).add(dom);
                }
            }
        }

        JSONObject result = new JSONObject();

        result.put("doms", doms);
        result.put("count", doms.size());

        return result;
    }

    @RequestMapping("/searchDom")
    public JSONObject searchDom(HttpServletRequest request) {

        JSONObject result = new JSONObject();
        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());
        String expr = WebUtils.required(request, "expr");

        List<Service> doms
            = serviceManager.searchDomains(namespaceId, ".*" + expr + ".*");

        if (CollectionUtils.isEmpty(doms)) {
            result.put("doms", Collections.emptyList());
            return result;
        }

        JSONArray domArray = new JSONArray();
        for (Service dom : doms) {
            domArray.add(dom.getName());
        }

        result.put("doms", domArray);

        return result;
    }

    @RequestMapping("/serviceStatus")
    public String serviceStatus(HttpServletRequest request) {
        //format: dom1@@checksum@@@dom2@@checksum
        String domsStatusString = WebUtils.required(request, "domsStatus");
        String serverIP = WebUtils.optional(request, "clientIP", "");

        if (!serverListManager.contains(serverIP)) {
            throw new IllegalArgumentException("ip: " + serverIP + " is not in serverlist");
        }

        try {
            ServiceManager.DomainChecksum checksums = JSON.parseObject(domsStatusString, ServiceManager.DomainChecksum.class);
            if (checksums == null) {
                Loggers.SRV_LOG.warn("[DOMAIN-STATUS] receive malformed data: null");
                return "fail";
            }

            for (Map.Entry<String, String> entry : checksums.domName2Checksum.entrySet()) {
                if (entry == null || StringUtils.isEmpty(entry.getKey()) || StringUtils.isEmpty(entry.getValue())) {
                    continue;
                }
                String dom = entry.getKey();
                String checksum = entry.getValue();
                Service domain = serviceManager.getService(checksums.namespaceId, dom);

                if (domain == null) {
                    continue;
                }

                domain.recalculateChecksum();

                if (!checksum.equals(domain.getChecksum())) {
                    if (Loggers.SRV_LOG.isDebugEnabled()) {
                        Loggers.SRV_LOG.debug("checksum of {} is not consistent, remote: {}, checksum: {}, local: {}",
                            dom, serverIP, checksum, domain.getChecksum());
                    }
                    serviceManager.addUpdatedDom2Queue(checksums.namespaceId, dom, serverIP, checksum);
                }
            }
        } catch (Exception e) {
            Loggers.SRV_LOG.warn("[DOMAIN-STATUS] receive malformed data: " + domsStatusString, e);
        }

        return "ok";
    }

    @RequestMapping(value = "/checksum", method = RequestMethod.PUT)
    public JSONObject checksum(HttpServletRequest request) {

        String namespaceId = WebUtils.optional(request, CommonParams.NAMESPACE_ID,
            UtilsAndCommons.getDefaultNamespaceId());
        String serviceName = WebUtils.required(request, "serviceName");
        Service service = serviceManager.getService(namespaceId, serviceName);

        if (service == null) {
            throw new IllegalArgumentException("serviceName not found: " + serviceName);
        }

        service.recalculateChecksum();

        JSONObject result = new JSONObject();

        result.put("checksum", service.getChecksum());

        return result;
    }

    private List<String> filterInstanceMetadata(String namespaceId, List<String> serivces, String key, String value) {

        List<String> filteredServices = new ArrayList<>();
        for (String service : serivces) {
            Service serviceObj = serviceManager.getService(namespaceId, service);
            if (serviceObj == null) {
                continue;
            }
            for (Instance address : serviceObj.allIPs()) {
                if (address.getMetadata() != null && value.equals(address.getMetadata().get(key))) {
                    filteredServices.add(service);
                    break;
                }
            }
        }
        return filteredServices;
    }

    private List<String> filterServiceMetadata(String namespaceId, List<String> serivces, String key, String value) {

        List<String> filteredServices = new ArrayList<>();
        for (String service : serivces) {
            Service serviceObj = serviceManager.getService(namespaceId, service);
            if (serviceObj == null) {
                continue;
            }
            if (value.equals(serviceObj.getMetadata().get(key))) {
                filteredServices.add(service);
            }

        }
        return filteredServices;
    }

    private Selector parseSelector(String selectorJsonString) throws NacosException {

        if (StringUtils.isBlank(selectorJsonString)) {
            return new NoneSelector();
        }

        JSONObject selectorJson = JSON.parseObject(selectorJsonString);
        switch (SelectorType.valueOf(selectorJson.getString("type"))) {
            case none:
                return new NoneSelector();
            case label:
                String expression = selectorJson.getString("expression");
                Set<String> labels = LabelSelector.parseExpression(expression);
                LabelSelector labelSelector = new LabelSelector();
                labelSelector.setExpression(expression);
                labelSelector.setLabels(labels);
                return labelSelector;
            default:
                throw new NacosException(NacosException.INVALID_PARAM, "not match any type of selector!");
        }
    }
}
