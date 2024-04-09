/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks.stats;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.time.Duration;
import java.time.Instant;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import de.dennisguse.opentracks.data.models.Altitude;
import de.dennisguse.opentracks.data.models.Distance;
import de.dennisguse.opentracks.data.models.HeartRate;
import de.dennisguse.opentracks.data.models.Speed;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.data.models.TrackPoint;

/**
 * Statistical data about a {@link Track}.
 * The data in this class should be filled out by {@link TrackStatisticsUpdater}.
 *
 * @author Rodrigo Damazio
 */
//TODO Use null instead of Double.isInfinite
//TODO Check that data ranges are valid (not less than zero etc.)
//TODO Should be a Java record
public class TrackStatistics {

    // The min and max altitude (meters) seen on this track.
    private final ExtremityMonitor altitudeExtremities = new ExtremityMonitor();

    // The track start time.
    private Instant startTime;
    // The track stop time.
    private Instant stopTime;

    private Distance totalDistance;
    // Updated when new points are received, may be stale.
    private Duration totalTime;
    // Based on when we believe the user is traveling.
    private Duration movingTime;
    // The maximum speed (meters/second) that we believe is valid.
    private Speed maxSpeed;
    private Float totalAltitudeGain_m = null;
    private Float totalAltitudeLoss_m = null;
    // Time spent in chairlift
    private Duration chairliftDuration = Duration.ZERO;
    // Flag to indicate chairlift status
    private boolean inChairlift = false;
    // The average heart rate seen on this track
    private HeartRate avgHeartRate = null;

    private boolean isIdle;

    private static final double CHAIRLIFT_RADIUS = 0.01;
    private static final double ELEVATION_THRESHOLD = 10; // The elevation threshold to detect the end of a run
    private static final double SPEED_THRESHOLD = 0.1; // The speed threshold to confirm the end of a run

    private Instant chairliftArrivalTime;
    private Instant activityStartTime;
    private Double lastElevation;
    private Duration totalWaitTime = Duration.ZERO;
    private Location userCurrLocation;

    private final Speed MAX_SPEED_THRESHOLD = Speed.of(2.0); // Threshold for maximum speed to detect chairlift activity
    private final float ALTITUDE_GAIN_THRESHOLD = 10.0f; // Threshold for altitude gain to detect chairlift activity
    private final float ALTITUDE_LOSS_THRESHOLD = 10.0f; // Threshold for altitude loss to detect chairlift activity

    private TrackPoint chairliftStartPoint = null;

    public TrackStatistics() {
        reset();
    }

    /**
     * Copy constructor.
     *
     * @param other another statistics data object to copy from
     */
    public TrackStatistics(TrackStatistics other) {
        startTime = other.startTime;
        stopTime = other.stopTime;
        totalDistance = other.totalDistance;
        totalTime = other.totalTime;
        movingTime = other.movingTime;
        maxSpeed = other.maxSpeed;
        altitudeExtremities.set(other.altitudeExtremities.getMin(), other.altitudeExtremities.getMax());
        totalAltitudeGain_m = other.totalAltitudeGain_m;
        totalAltitudeLoss_m = other.totalAltitudeLoss_m;
        avgHeartRate = other.avgHeartRate;
        isIdle = other.isIdle;
        chairliftDuration = other.chairliftDuration;
        inChairlift = other.inChairlift;
        chairliftStartPoint = other.chairliftStartPoint;

    }

    @VisibleForTesting
    public TrackStatistics(String startTime, String stopTime, double totalDistance_m, int totalTime_s, int movingTime_s, float maxSpeed_mps, Float totalAltitudeGain_m, Float totalAltitudeLoss_m) {
        this.startTime = Instant.parse(startTime);
        this.stopTime = Instant.parse(stopTime);
        this.totalDistance = Distance.of(totalDistance_m);
        this.totalTime = Duration.ofSeconds(totalTime_s);
        this.movingTime = Duration.ofSeconds(movingTime_s);
        this.maxSpeed = Speed.of(maxSpeed_mps);
        this.totalAltitudeGain_m = totalAltitudeGain_m;
        this.totalAltitudeLoss_m = totalAltitudeLoss_m;
    }

    /**
     * Combines these statistics with those from another object.
     * This assumes that the time periods covered by each do not intersect.
     *
     * @param other another statistics data object
     */
    public void merge(TrackStatistics other) {
        if (startTime == null) {
            startTime = other.startTime;
        } else {
            startTime = startTime.isBefore(other.startTime) ? startTime : other.startTime;
        }
        if (stopTime == null) {
            stopTime = other.stopTime;
        } else {
            stopTime = stopTime.isAfter(other.stopTime) ? stopTime : other.stopTime;
        }

        if (avgHeartRate == null) {
            avgHeartRate = other.avgHeartRate;
        } else {
            if (other.avgHeartRate != null) {
                // Using total time as weights for the averaging.
                // Important to do this before total time is updated
                avgHeartRate = HeartRate.of(
                        (totalTime.getSeconds() * avgHeartRate.getBPM() + other.totalTime.getSeconds() * other.avgHeartRate.getBPM())
                                / (totalTime.getSeconds() + other.totalTime.getSeconds())
                );
            }
        }

        totalDistance = totalDistance.plus(other.totalDistance);
        totalTime = totalTime.plus(other.totalTime);
        movingTime = movingTime.plus(other.movingTime);
        maxSpeed = Speed.max(maxSpeed, other.maxSpeed);
        if (other.altitudeExtremities.hasData()) {
            altitudeExtremities.update(other.altitudeExtremities.getMin());
            altitudeExtremities.update(other.altitudeExtremities.getMax());
        }
        if (totalAltitudeGain_m == null) {
            if (other.totalAltitudeGain_m != null) {
                totalAltitudeGain_m = other.totalAltitudeGain_m;
            }
        } else {
            if (other.totalAltitudeGain_m != null) {
                totalAltitudeGain_m += other.totalAltitudeGain_m;
            }
        }
        if (totalAltitudeLoss_m == null) {
            if (other.totalAltitudeLoss_m != null) {
                totalAltitudeLoss_m = other.totalAltitudeLoss_m;
            }
        } else {
            if (other.totalAltitudeLoss_m != null) {
                totalAltitudeLoss_m += other.totalAltitudeLoss_m;
            }
        }
        if (chairliftDuration.isZero()) {
            chairliftDuration = other.chairliftDuration;
        } else {
            chairliftDuration = chairliftDuration.plus(other.chairliftDuration);
        }
        if (chairliftStartPoint == null) {
            if(other.chairliftStartPoint != null) {
                chairliftStartPoint = other.chairliftStartPoint;
            }
        } else {
            if (other.chairliftStartPoint != null) {
                if (chairliftStartPoint.getTime().isAfter(other.chairliftStartPoint.getTime())) {
                    chairliftStartPoint = other.chairliftStartPoint;
                }
            }
        }

    }

    public boolean isInitialized() {
        return startTime != null;
    }

    public void reset() {
        startTime = null;
        stopTime = null;
        chairliftStartPoint = null;
        chairliftDuration = Duration.ZERO;
        inChairlift = false;

        setTotalDistance(Distance.of(0));
        setTotalTime(Duration.ofSeconds(0));
        setMovingTime(Duration.ofSeconds(0));
        setMaxSpeed(Speed.zero());
        setTotalAltitudeGain(null);
        setTotalAltitudeLoss(null);

        isIdle = false;
    }

    public void reset(Instant startTime) {
        reset();
        setStartTime(startTime);
    }

    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Should only be called on start.
     */
    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
        setStopTime(startTime);
    }

    public Instant getStopTime() {
        return stopTime;
    }

    public void setStopTime(Instant stopTime) {
        if (stopTime.isBefore(startTime)) {
            // Time must be monotonically increasing, but we might have events at the same point in time (BLE and GPS)
            throw new RuntimeException("stopTime cannot be less than startTime: " + startTime + " " + stopTime);
        }
        this.stopTime = stopTime;
    }

    public Distance getTotalDistance() {
        return totalDistance;
    }

    public void setTotalDistance(Distance totalDistance_m) {
        this.totalDistance = totalDistance_m;
    }

    public void addTotalDistance(Distance distance_m) {
        totalDistance = totalDistance.plus(distance_m);
    }

    /**
     * Gets the total time in milliseconds that this track has been active.
     * This statistic is only updated when a new point is added to the statistics, so it may be off.
     * If you need to calculate the proper total time, use {@link #getStartTime} with the current time.
     */
    public Duration getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(Duration totalTime) {
        this.totalTime = totalTime;
    }

    public Duration getMovingTime() {
        return movingTime;
    }

    public void setMovingTime(Duration movingTime) {
        this.movingTime = movingTime;
    }

    public void addMovingTime(TrackPoint trackPoint, TrackPoint lastTrackPoint) {
        addMovingTime(Duration.between(lastTrackPoint.getTime(), trackPoint.getTime()));
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public void addMovingTime(Duration time) {
        if (time.isNegative()) {
            throw new RuntimeException("Moving time cannot be negative");
        }
        movingTime = movingTime.plus(time);
    }

    public Duration getStoppedTime() {
        return totalTime.minus(movingTime);
    }

    public boolean isIdle() {
        return isIdle;
    }

    public void setIdle(boolean idle) {
        isIdle = idle;
    }

    public boolean hasAverageHeartRate() {
        return avgHeartRate != null;
    }

    @Nullable
    public HeartRate getAverageHeartRate() {
        return avgHeartRate;
    }

    /**
     * Gets the average speed.
     * This calculation only takes into account the displacement until the last point that was accounted for in statistics.
     */
    public Speed getAverageSpeed() {
        if (totalTime.isZero()) {
            return Speed.of(0);
        }
        return Speed.of(totalDistance.toM() / totalTime.getSeconds());
    }

    public Speed getAverageMovingSpeed() {
        return Speed.of(totalDistance, movingTime);
    }

    public Speed getMaxSpeed() {
        return Speed.max(maxSpeed, getAverageMovingSpeed());
    }

    public void setMaxSpeed(Speed maxSpeed) {
        this.maxSpeed = maxSpeed;
    }

    public boolean hasAltitudeMin() {
        return !Double.isInfinite(getMinAltitude());
    }

    public double getMinAltitude() {
        return altitudeExtremities.getMin();
    }

    public void setMinAltitude(double altitude_m) {
        altitudeExtremities.setMin(altitude_m);
    }

    public boolean hasAltitudeMax() {
        return !Double.isInfinite(getMaxAltitude());
    }

    /**
     * Gets the maximum altitude.
     * This is calculated from the smoothed altitude, so this can actually be less than the current altitude.
     */
    public double getMaxAltitude() {
        return altitudeExtremities.getMax();
    }

    public void setMaxAltitude(double altitude_m) {
        altitudeExtremities.setMax(altitude_m);
    }

    public void updateAltitudeExtremities(Altitude altitude) {
        if (altitude != null) {
            altitudeExtremities.update(altitude.toM());
        }
    }

    public void setAverageHeartRate(HeartRate heartRate) {
        if (heartRate != null) {
            avgHeartRate = heartRate;
        }
    }

    // Start chairlift activity
    public void startChairliftActivity(Instant time, double elevation) {
        activityStartTime = time;
        lastElevation = elevation;
    }

    // Update the location of the chairlift
    public void updateChairliftLocation(double latitude, double longitude, double elevation, Instant time) {
        if (activityStartTime == null) {
            lastElevation = elevation;
            return;
        }

        // If user is near a chairlift
        if (isNearChairlift(latitude, longitude)) {
            // Set the arrival time if not already set
            if (chairliftArrivalTime == null) {
                chairliftArrivalTime = time;
            }
        } else {
            // If user was on the chairlift and now away from it
            if (chairliftArrivalTime != null) {
                Duration waitTime = Duration.between(chairliftArrivalTime, time);
                totalWaitTime = totalWaitTime.plus(waitTime);

                chairliftArrivalTime = null;
            }

            if (isEndOfRun(elevation, time)) {
                activityStartTime = null; // End the activity
            }
        }

        lastElevation = elevation;
    }

    // Checks if chairlift activity is completed
    public boolean isEndOfRun(double elevation, Instant time) {
        if (lastElevation != null && (elevation - lastElevation) > ELEVATION_THRESHOLD) {
            Duration timeDiff = Duration.between(activityStartTime, time);
            double speed = (elevation - lastElevation) / timeDiff.getSeconds();

            return speed >= SPEED_THRESHOLD;
        }

        return false;
    }

    private boolean isNearChairlift(double chairliftLatitude, double chairliftLongitude)
    {
        //current latitude and longitude
        double curr_user_latitude = userCurrLocation.getLatitude();
        double curr_user_longitude = userCurrLocation.getLongitude();

        // Haversine formula for caluculating distance between two points on earth
        double earthRadius = 6371; // Radius of earth in Kilometers
        double d_Latitude = Math.toRadians(chairliftLatitude - curr_user_latitude);
        double d_Longitude = Math.toRadians(chairliftLongitude - curr_user_longitude);

        double a = Math.sin(d_Latitude / 2) * Math.sin(d_Latitude / 2) +
                Math.cos(Math.toRadians(curr_user_latitude)) * Math.cos(Math.toRadians(chairliftLatitude)) *
                        Math.sin(d_Longitude / 2) * Math.sin(d_Longitude / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double distance = earthRadius * c; // Distance in kilometers

        return distance <= CHAIRLIFT_RADIUS;
    }

    public Duration getTotalWaitTime() {
        return totalWaitTime;
    }

    public boolean hasTotalAltitudeGain() {
        return totalAltitudeGain_m != null;
    }

    @Nullable
    public Float getTotalAltitudeGain() {
        return totalAltitudeGain_m;
    }

    public void setTotalAltitudeGain(Float totalAltitudeGain_m) {
        this.totalAltitudeGain_m = totalAltitudeGain_m;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public void addTotalAltitudeGain(float gain_m) {
        if (totalAltitudeGain_m == null) {
            totalAltitudeGain_m = 0f;
        }
        totalAltitudeGain_m += gain_m;
    }

    public boolean hasTotalAltitudeLoss() {
        return totalAltitudeLoss_m != null;
    }

    @Nullable
    public Float getTotalAltitudeLoss() {
        return totalAltitudeLoss_m;
    }

    public void setTotalAltitudeLoss(Float totalAltitudeLoss_m) {
        this.totalAltitudeLoss_m = totalAltitudeLoss_m;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public void addTotalAltitudeLoss(float loss_m) {
        if (totalAltitudeLoss_m == null) {
            totalAltitudeLoss_m = 0f;
        }
        totalAltitudeLoss_m += loss_m;
    }

    public void updateChairliftTime(TrackPoint trackPoint){
        // get the speed if available else take zero
        Speed speed = trackPoint.hasSpeed() ? trackPoint.getSpeed() : Speed.zero();
        // get the altitude gain if available else take zero
        float altitudeGain = trackPoint.hasAltitudeGain() ? trackPoint.getAltitudeGain() : 0.0f;
        // Get the altitude loss if available else take zero
        float altitudeLoss = trackPoint.hasAltitudeLoss() ? trackPoint.getAltitudeLoss() : 0.0f;

        if (!inChairlift && !speed.isInvalid() &&
                MAX_SPEED_THRESHOLD.greaterOrEqualThan(speed)
                &&
                altitudeGain >= ALTITUDE_GAIN_THRESHOLD) {
            // Entering chairlift zone
            inChairlift = true;
            chairliftStartPoint = trackPoint;
        } else if (inChairlift && (MAX_SPEED_THRESHOLD.lessThan(speed) || altitudeLoss > ALTITUDE_LOSS_THRESHOLD)) {
            // Leaving chairlift zone
            inChairlift = false;
        }
        if(inChairlift){
            chairliftDuration = chairliftDuration.plus(Duration.between(chairliftStartPoint.getTime(), trackPoint.getTime()));
        }
    }
    public Duration getChairliftTime(){
        return chairliftDuration;
    }

    public Duration getTotalChairliftTime(){
        return chairliftDuration.plus(totalWaitTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrackStatistics)) return false;

        return toString().equals(o.toString());
    }

    @NonNull
    @Override
    public String toString() {
        return "TrackStatistics { Start Time: " + getStartTime() + "; Stop Time: " + getStopTime()
                + "; Total Distance: " + getTotalDistance() + "; Total Time: " + getTotalTime()
                + "; Moving Time: " + getMovingTime() + "; Max Speed: " + getMaxSpeed()
                + "; Min Altitude: " + getMinAltitude() + "; Max Altitude: " + getMaxAltitude()
                + "; Altitude Gain: " + getTotalAltitudeGain()
                + "; Altitude Loss: " + getTotalAltitudeLoss()
                + "; Time in Chairlift: " + getChairliftTime()
                + "; Waiting Time at Chairlift: " + getTotalWaitTime()
                + "; Total time at Chairlift (including waiting time): " + getTotalChairliftTime()
                + "}";
    }


    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // Haversine formula for calculating distance between two points
        double R = 6371e3; // meters
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                        Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c; // Distance in meters
    }

    public static double calculateAverageSpeedFromGpx(String filePath) throws Exception {
        File gpxFile = new File(filePath);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(gpxFile);

        doc.getDocumentElement().normalize();
        NodeList trkptList = doc.getElementsByTagName("trkpt");

        double totalDistance = 0.0;
        long totalTime = 0;
        double lat1, lon1, lat2 = 0, lon2 = 0;
        long time1, time2 = 0;

        for (int i = 0; i < trkptList.getLength() - 1; i++) {
            Element pt1 = (Element) trkptList.item(i);
            Element pt2 = (Element) trkptList.item(i + 1);

            lat1 = Double.parseDouble(pt1.getAttribute("lat"));
            lon1 = Double.parseDouble(pt1.getAttribute("lon"));
            lat2 = Double.parseDouble(pt2.getAttribute("lat"));
            lon2 = Double.parseDouble(pt2.getAttribute("lon"));

            String timeStr1 = ((Element) pt1.getElementsByTagName("time").item(0)).getTextContent();
            String timeStr2 = ((Element) pt2.getElementsByTagName("time").item(0)).getTextContent();

            Instant instant1 = Instant.parse(timeStr1);
            Instant instant2 = Instant.parse(timeStr2);

            time1 = instant1.getEpochSecond();
            time2 = instant2.getEpochSecond();

            totalDistance += calculateDistance(lat1, lon1, lat2, lon2);
            totalTime += (time2 - time1);
        }

        // Ensure division by time is not zero
        if (totalTime == 0) return 0.0;
        return totalDistance / totalTime; // Average speed in meters per second
    }


}

