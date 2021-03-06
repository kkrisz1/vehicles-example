package org.example.vehicles.backend.service.vehicle.dao;

import lombok.extern.slf4j.Slf4j;
import org.example.vehicles.common.vehicle.entity.Location;
import org.example.vehicles.common.vehicle.entity.Vehicle;
import org.example.vehicles.common.vehicle.entity.VehicleBeacon;
import org.example.vehicles.common.vehicle.entity.Vehicles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * <pre>
 *     Learn from:
 *     <a href="http://janmatuschek.de/LatitudeLongitudeBoundingCoordinates">LatitudeLongitudeBoundingCoordinates</a>
 *     and
 *     <a href="https://www.movable-type.co.uk/scripts/latlong-db.html">latlong-db</a>
 * </pre>
 */
@Service
@Slf4j
public class VehicleDaoImpl implements VehicleDao {
    private static final double RADIUS_EARTH = 6371e3d;

    private final ConcurrentMap<UUID, Vehicle> vehicleMap = new ConcurrentHashMap<>();
    private final Duration timeWindow;

    public VehicleDaoImpl(@Value("${app.time-window:5s}") Duration timeWindow) {
        this.timeWindow = timeWindow;
    }

    @Override
    public Vehicle registerVehicle() {
        return vehicleMap.computeIfAbsent(UUID.randomUUID(), uuid ->
                Vehicle.builder()
                        .id(uuid)
                        .registrationDateTime(OffsetDateTime.now())
                        .build());
    }

    @Override
    public void postPosition(UUID id, Location location) {
        // always save the position
        // (vehicleMap.computeIfPresent() can be used
        // if it is mandatory to record the location only for the registered vehicles)
        vehicleMap.compute(id, (uuid, vehicle) -> {
            final OffsetDateTime positionDateTime = OffsetDateTime.now();

            if (vehicle == null) {
                log.warn("Unknown vehicle: " + uuid);
                return Vehicle.builder()
                        .id(uuid)
                        .location(location)
                        .positionDateTime(positionDateTime)
                        .registrationDateTime(positionDateTime)
                        .build();
            }

            vehicle.setPositionDateTime(positionDateTime);
            vehicle.setLocation(location);
            return vehicle;
        });
    }

    @Override
    public Vehicles getVehicleWithinCircle(VehicleBeacon beacon) {
        final double angle = beacon.getRadius() / RADIUS_EARTH;
        final double centerRadLong = Math.toRadians(beacon.getVehicle().getLocation().getLongitude());
        final double centerRadLat = Math.toRadians(beacon.getVehicle().getLocation().getLatitude());
        final OffsetDateTime beaconDateTime = OffsetDateTime.now();

        return vehicleMap.values().parallelStream()
                .filter(v -> Objects.nonNull(v.getLocation()))
                .filter(v -> !beacon.getVehicle().getId().equals(v.getId()))
                .filter(v -> v.getPositionDateTime().isAfter(beaconDateTime.minus(timeWindow)))
                .filter(v -> {
                    final double latMin = centerRadLat - angle;
                    final double latMax = centerRadLat + angle;
                    final double radLat = Math.toRadians(v.getLocation().getLatitude());
                    return radLat >= latMin && radLat <= latMax;
                })
                .filter(v -> {
                    final double lngDelta = Math.asin(Math.sin(angle) * Math.cos(centerRadLat));
                    final double radLng = Math.toRadians(v.getLocation().getLongitude());
                    final double lngMin = centerRadLong - lngDelta;
                    final double lngMax = centerRadLong + lngDelta;
                    return radLng >= lngMin && radLng <= lngMax;
                })
                .filter(v -> {
                    // dist = arccos(sin(lat1) · sin(lat2) + cos(lat1) · cos(lat2) · cos(lon1 - lon2)) · R
                    double radLong2 = Math.toRadians(v.getLocation().getLongitude());
                    double radLat2 = Math.toRadians(v.getLocation().getLatitude());
                    double dist = Math.acos(
                            (Math.sin(centerRadLat) * Math.sin(radLat2))
                                    + Math.cos(centerRadLat) * Math.cos(radLat2) * Math.cos(radLong2 - centerRadLong))
                            * RADIUS_EARTH;
                    return dist < beacon.getRadius();
                })
                .collect(Vehicles::builder, Vehicles.VehiclesBuilder::vehicle,
                        (builder, builder2) -> builder.vehicles(builder2.build().getVehicles()))
                .build();
    }
}
