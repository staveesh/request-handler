package com.taveeshsharma.httprequesthandler.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.taveeshsharma.httprequesthandler.utils.ApiUtils;
import com.taveeshsharma.httprequesthandler.utils.Constants;
import com.taveeshsharma.httprequesthandler.dto.documents.PersonalData;
import com.taveeshsharma.httprequesthandler.dto.documents.ScheduleRequest;
import com.taveeshsharma.httprequesthandler.measurements.*;
import com.taveeshsharma.httprequesthandler.repository.PersonalDataRepository;
import com.taveeshsharma.httprequesthandler.repository.ScheduleRequestRepository;
import org.apache.commons.math3.util.Precision;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.influxdb.impl.InfluxDBResultMapper;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.influxdb.InfluxDBTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class DatabaseManagerImpl implements DatabaseManager{

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManagerImpl.class);

    @Autowired
    private ScheduleRequestRepository scheduleRequestRepository;

    @Autowired
    private PersonalDataRepository personalDataRepository;

    @Autowired
    private InfluxDBTemplate<Point> influxDBTemplate;

    @Value("${spring.influxdb.database}")
    private String DB_NAME;

    @Override
    public void insertScheduledJob(ScheduleRequest request) {
        if (request.getRequestType().equals(Constants.RequestType.SCHEDULE_MEASUREMENT.toString())) {
            scheduleRequestRepository.insert(request);
        }
    }

    @Override
    public JSONObject getMeasurement(String id, String type) {
        QueryResult queryResult;
        if(id == null || id.isEmpty())
            queryResult = influxDBTemplate.query(new Query(
                    String.format("SELECT * FROM %s", type),
                    DB_NAME
            ));
        else
            queryResult = influxDBTemplate.query(
                    new Query(
                            String.format("SELECT * FROM %s WHERE taskKey = \'%s\'", type, id),
                            DB_NAME
                    ));
        Gson gson = new GsonBuilder().create();
        InfluxDBResultMapper resultMapper = new InfluxDBResultMapper();
        switch (type.toUpperCase()) {
            case Constants.TCP_TYPE:
                return new JSONObject(gson.toJson(resultMapper.toPOJO(queryResult, TCPMeasurement.class)));
            case Constants.PING_TYPE:
                return new JSONObject(gson.toJson(resultMapper.toPOJO(queryResult, PingMeasurement.class)));
            case Constants.DNS_TYPE:
                return new JSONObject(gson.toJson(resultMapper.toPOJO(queryResult, DNSLookupMeasurement.class)));
            case Constants.HTTP_TYPE:
                return new JSONObject(gson.toJson(resultMapper.toPOJO(queryResult, HTTPMeasurement.class)));
            case Constants.TRACERT_TYPE:
                return new JSONObject(gson.toJson(resultMapper.toPOJO(queryResult, TracerouteMeasurement.class)));
            default:
                return null;
        }
    }

    @Override
    public List<ScheduleRequest> getScheduledJobs(String type) {
        return scheduleRequestRepository.getScheduledJobsFromType(type);
    }

    @Override
    public void writeValues(JSONObject jsonObject) {
        String type = (String) jsonObject.get("type");
        logger.info("Type : "+type);
        logger.info("Writing to influxdb : "+jsonObject.toString());
        Point p;
        switch (type) {
            case Constants.TCP_TYPE:
                p = createTCPPoint(jsonObject);
                break;
            case Constants.PING_TYPE:
                p = createPingPoint(jsonObject);
                break;
            case Constants.DNS_TYPE:
                p = createDNSPoint(jsonObject);
                break;
            case Constants.HTTP_TYPE:
                p = createHttpPoint(jsonObject);
                break;
            case Constants.TRACERT_TYPE:
                try {
                    logger.info("Traceroute : "+jsonObject);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                return;
            default:
                p = null;
                break;
        }
        influxDBTemplate.write(p);
    }

    private Point createTCPPoint(JSONObject jsonObject){
        JSONObject measurementValues = jsonObject.getJSONObject("values");
        long time = jsonObject.getLong("timestamp");
        TCPMeasurement tcpMeasurement = (TCPMeasurement) buildMeasurements(jsonObject, TCPMeasurement.class);

        tcpMeasurement.setSpeedValues(measurementValues.getString("tcp_speed_results"));
        tcpMeasurement.setDataLimitExceeded(Boolean.parseBoolean(measurementValues.getString("data_limit_exceeded")));
        double duration = Double.parseDouble(measurementValues.getString("duration"));
        tcpMeasurement.setMeasurementDuration(Precision.round(duration, 2));
        return Point.measurementByPOJO(TCPMeasurement.class)
                .time(time, TimeUnit.MICROSECONDS)
                .addFieldsFromPOJO(tcpMeasurement)
                .build();
    }

    private Point createPingPoint(JSONObject jsonObject) {
        JSONObject measurementValues = jsonObject.getJSONObject("values");
        long time = jsonObject.getLong("timestamp");

        PingMeasurement pingMeasurement = (PingMeasurement) buildMeasurements(jsonObject, PingMeasurement.class);
        double mean, max, std;

        mean = Double.parseDouble(measurementValues.getString("mean_rtt_ms"));
        max = Double.parseDouble(measurementValues.getString("max_rtt_ms"));
        std = Double.parseDouble(measurementValues.getString("stddev_rtt_ms"));

        pingMeasurement.setTargetIpAddress(measurementValues.getString("target_ip"));
        pingMeasurement.setPingMethod(measurementValues.getString("ping_method"));
        pingMeasurement.setMeanRttMS(Precision.round(mean, 2));
        pingMeasurement.setMaxRttMs(Precision.round(max, 2));
        pingMeasurement.setStddevRttMs(Precision.round(std, 2));
        return Point.measurementByPOJO(PingMeasurement.class)
                .time(time, TimeUnit.MICROSECONDS)
                .addFieldsFromPOJO(pingMeasurement)
                .build();
    }

    private Point createDNSPoint(JSONObject jsonObject) {
        JSONObject measurementValues = jsonObject.getJSONObject("values");
        long time = jsonObject.getLong("timestamp");

        DNSLookupMeasurement dnsLookupMeasurement = (DNSLookupMeasurement) buildMeasurements(jsonObject, DNSLookupMeasurement.class);

        dnsLookupMeasurement.setHostAddress(measurementValues.getString("address"));
        dnsLookupMeasurement.setHostName(measurementValues.getString("realHostname"));
        dnsLookupMeasurement.setTimeTaken(measurementValues.getDouble("timeMs"));

        return Point.measurementByPOJO(DNSLookupMeasurement.class)
                .time(time, TimeUnit.MICROSECONDS)
                .addFieldsFromPOJO(dnsLookupMeasurement)
                .build();
    }

    private Point createHttpPoint(JSONObject jsonObject) {
        JSONObject measurementValues = jsonObject.getJSONObject("values");
        long time = jsonObject.getLong("timestamp");

        HTTPMeasurement httpMeasurement = (HTTPMeasurement) buildMeasurements(jsonObject, HTTPMeasurement.class);

        httpMeasurement.setHttpResultCode(Integer.parseInt(measurementValues.getString("code")));
        double duration = Double.parseDouble(measurementValues.getString("time_ms"));
        httpMeasurement.setTimeTakenMs(Precision.round(duration, 2));

        return Point.measurementByPOJO(HTTPMeasurement.class)
                .time(time, TimeUnit.MICROSECONDS)
                .addFieldsFromPOJO(httpMeasurement)
                .build();
    }

    private Measurements buildMeasurements(JSONObject object, Class<? extends Measurements> T){
        try {
            Measurements measurements = T.newInstance();
            String user = object.getString("accountName");
            measurements.setUserName(ApiUtils.hashUserName(user));
            measurements.setExperiment(object.getBoolean("isExperiment"));
            measurements.setTarget(getTargetKey(object.getJSONObject("parameters"), object.getString("type")));
            if (measurements.getIsExperiment())
                measurements.setTaskKey(object.getString("taskKey"));
            else
                measurements.setTaskKey("N.A");
            return measurements;
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getTargetKey(JSONObject object, String type) {
        switch (type) {
            case Constants.TCP_TYPE:
            case Constants.TRACERT_TYPE:
            case Constants.DNS_TYPE:
            case Constants.PING_TYPE:
                return object.getString("target");
            case Constants.HTTP_TYPE:
                return object.getString("url");
            default:
                return "";
        }
    }

    @Override
    public void writePersonalData(PersonalData data) {
        try {
            data.setUserName(ApiUtils.hashUserName(data.getUserName()));
            logger.info("Writing personal data : "+data);
            personalDataRepository.save(data);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
