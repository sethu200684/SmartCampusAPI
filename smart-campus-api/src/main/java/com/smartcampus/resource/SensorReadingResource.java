package com.smartcampus.resource;

import com.smartcampus.exception.SensorUnavailableException;
import com.smartcampus.model.Sensor;
import com.smartcampus.model.SensorReading;
import com.smartcampus.store.DataStore;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Part 4 - Sub-Resource for Sensor Readings
 * Handles /api/v1/sensors/{sensorId}/readings
 *
 * This class is instantiated by SensorResource's sub-resource locator,
 * NOT auto-scanned by Jersey — no @Path annotation at class level needed.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SensorReadingResource {

    private final String sensorId;
    private final DataStore store = DataStore.getInstance();

    public SensorReadingResource(String sensorId) {
        this.sensorId = sensorId;
    }

    /**
     * GET /api/v1/sensors/{sensorId}/readings
     * Returns all historical readings for this sensor.
     */
    @GET
    public Response getReadings() {
        List<SensorReading> readings = store.getSensorReadings().get(sensorId);
        if (readings == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorBody("No reading history found for sensor '" + sensorId + "'."))
                    .build();
        }
        return Response.ok(readings).build();
    }

    /**
     * POST /api/v1/sensors/{sensorId}/readings
     * Appends a new reading for this sensor.
     * Side effect: updates the sensor's currentValue.
     * Throws SensorUnavailableException (-> 403) if sensor status is MAINTENANCE.
     */
    @POST
    public Response addReading(SensorReading reading) {
        Sensor sensor = store.getSensors().get(sensorId);

        // State constraint: MAINTENANCE sensors cannot accept readings
        if ("MAINTENANCE".equalsIgnoreCase(sensor.getStatus())) {
            throw new SensorUnavailableException(
                "Sensor '" + sensorId + "' is currently under MAINTENANCE and cannot accept new readings.");
        }

        if ("OFFLINE".equalsIgnoreCase(sensor.getStatus())) {
            throw new SensorUnavailableException(
                "Sensor '" + sensorId + "' is OFFLINE and cannot accept new readings.");
        }

        // Create a proper reading with auto-generated ID and timestamp
        SensorReading newReading = new SensorReading(reading.getValue());

        // Side effect: update the sensor's currentValue for data consistency
        sensor.setCurrentValue(newReading.getValue());

        store.getSensorReadings()
             .computeIfAbsent(sensorId, k -> new java.util.ArrayList<>())
             .add(newReading);

        return Response.status(Response.Status.CREATED).entity(newReading).build();
    }

    /**
     * GET /api/v1/sensors/{sensorId}/readings/{readingId}
     * Returns a specific reading by ID.
     */
    @GET
    @Path("/{readingId}")
    public Response getReadingById(@PathParam("readingId") String readingId) {
        List<SensorReading> readings = store.getSensorReadings().get(sensorId);
        if (readings == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorBody("No readings found for sensor '" + sensorId + "'.")).build();
        }
        return readings.stream()
                .filter(r -> r.getId().equals(readingId))
                .findFirst()
                .map(r -> Response.ok(r).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(errorBody("Reading '" + readingId + "' not found.")).build());
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
