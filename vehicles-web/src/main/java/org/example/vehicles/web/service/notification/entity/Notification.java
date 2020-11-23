package org.example.vehicles.web.service.notification.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.UUID;

@Data
public class Notification {
    @JsonProperty("vehicle_id")
    UUID vehicleId;
    String message;
}
