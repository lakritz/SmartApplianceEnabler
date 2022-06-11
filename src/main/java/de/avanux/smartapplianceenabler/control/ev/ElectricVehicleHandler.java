/*
 * Copyright (C) 2022 Axel Müller <axel.mueller@avanux.de>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package de.avanux.smartapplianceenabler.control.ev;

import de.avanux.smartapplianceenabler.appliance.ApplianceIdConsumer;
import de.avanux.smartapplianceenabler.mqtt.MeterMessage;
import de.avanux.smartapplianceenabler.schedule.Request;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ElectricVehicleHandler implements ApplianceIdConsumer, SocScriptExecutionResultListener {

    private transient Logger logger = LoggerFactory.getLogger(ElectricVehicleHandler.class);
    private DecimalFormat percentageFormat;
    private String applianceId;
    private List<ElectricVehicle> vehicles;
    private Map<Integer, SocScriptExecutor> evIdWithSocScriptExecutor = new HashMap<>();
    /**
     * Is only set if connected vehicle has been identified
     */
    private Integer connectedVehicleId;
    private double socRetrievalEnergyMeterValue = 0.0f;
    private SocValues socValues = new SocValues();
    private boolean socCalculationRequired;
    private double chargeLoss = 0.0;
    private MeterMessage meterMessage;
    private boolean socRetrievalForChargingAlmostCompleted;
    private boolean chargingAlmostCompleted;

    public ElectricVehicleHandler() {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.ENGLISH);
        percentageFormat = (DecimalFormat) nf;
        percentageFormat.applyPattern("#'%'");
    }

    @Override
    public void setApplianceId(String applianceId) {
        this.applianceId = applianceId;
    }

    public void setVehicles(List<ElectricVehicle> vehicles) {
        this.vehicles = vehicles;
        this.vehicles.forEach(vehicle -> {
            SocScriptExecutor executor = new SocScriptExecutor(vehicle.getId(), vehicle.getSocScript());
            executor.setApplianceId(this.applianceId);
            this.evIdWithSocScriptExecutor.put(vehicle.getId(), executor);
        });
    }

    public void setMeterMessage(MeterMessage meterMessage) {
        this.meterMessage = meterMessage;
    }

    public void onVehicleDisconnected() {
        this.connectedVehicleId = null;
        this.socValues = new SocValues();
        this.socRetrievalForChargingAlmostCompleted = false;
        this.socRetrievalEnergyMeterValue = 0.0f;
    }

    public void onChargingCompleted() {
        this.socRetrievalForChargingAlmostCompleted = false;
    }

    public Integer getConnectedOrFirstVehicleId() {
        if(this.connectedVehicleId != null) {
            return this.connectedVehicleId;
        }
        if(this.vehicles.size() > 0) {
            return this.vehicles.get(0).getId();
        }
        return null;
    }

    public void setConnectedVehicleId(Integer connectedVehicleId) {
        this.connectedVehicleId = connectedVehicleId;
    }

    public ElectricVehicle getConnectedVehicle() {
        Integer evId = getConnectedOrFirstVehicleId();
        if(evId != null) {
            return getVehicle(evId);
        }
        return null;
    }

    public ElectricVehicle getVehicle(int evId) {
        if(this.vehicles != null) {
            for(ElectricVehicle electricVehicle : this.vehicles) {
                if(electricVehicle.getId() == evId) {
                    return electricVehicle;
                }
            }
        }
        return null;
    }

    public SocValues getSocValues() {
        return socValues;
    }

    public Integer getSocInitial() {
        return this.socValues.initial != null ? this.socValues.initial : 0;
    }

    public void setSocInitial(Integer socInitial) {
        this.socValues.initial = socInitial;
    }

    public Integer getSocCurrent() {
        return this.socValues.current != null ? this.socValues.current : 0;
    }

    public void setSocCurrent(Integer socCurrent) {
        this.socValues.current = socCurrent;
    }

    public Long getSocInitialTimestamp() {
        ZoneOffset zoneOffset = ZoneId.systemDefault().getRules().getOffset(LocalDateTime.now());
        return socValues.initialTimestamp != null ? socValues.initialTimestamp.toEpochSecond(zoneOffset) * 1000 : null;
    }

    public void setSocInitialTimestamp(LocalDateTime socInitialTimestamp) {
        if(this.socValues.initialTimestamp == null) {
            this.socValues.initialTimestamp = socInitialTimestamp;
        }
    }

    public void setSocCalculationRequired(boolean socCalculationRequired) {
        this.socCalculationRequired = socCalculationRequired;
    }

    public boolean updateSoc(LocalDateTime now, Request request) {
        boolean socChanged = false;
        if(this.socCalculationRequired) {
            int calculatedCurrentSoc = calculateCurrentSoc();
            socChanged = this.socValues.current != null && this.socValues.current != calculatedCurrentSoc;
            this.socValues.current = calculatedCurrentSoc;
            if(socChanged) {
                if(request != null) {
                    Integer max = request.getMax(now);
                    if(max < 1000) {
                        chargingAlmostCompleted = true;
                    }
                }

            }
            this.socCalculationRequired = false;
        }
        logger.debug( "{}: SOC retrieval: socCalculationRequired={} socChanged={} chargingAlmostCompleted={} socRetrievalForChargingAlmostCompleted={}",
                applianceId, socCalculationRequired, socChanged, chargingAlmostCompleted, socRetrievalForChargingAlmostCompleted);

        ElectricVehicle electricVehicle = getConnectedVehicle();
        if(electricVehicle != null && electricVehicle.getSocScript() != null) {
            Integer updateAfterIncrease = electricVehicle.getSocScript().getUpdateAfterIncrease();
            if(updateAfterIncrease == null) {
                updateAfterIncrease = ElectricVehicleChargerDefaults.getUpdateSocAfterIncrease();
            }
            Integer updateAfterSeconds = electricVehicle.getSocScript().getUpdateAfterSeconds();
            if(this.socValues.initial == null
                    || this.socValues.retrieved == null
                    || (chargingAlmostCompleted && !socRetrievalForChargingAlmostCompleted)
                    || ((this.socValues.retrieved + updateAfterIncrease <= this.socValues.current)
                    && (updateAfterSeconds == null || now.minusSeconds(updateAfterSeconds).isAfter(this.socValues.retrievedTimestamp)))
            ) {
                logger.debug( "{}: SOC retrieval is required: {}", applianceId, this.socValues);
                triggerSocScriptExecution(getConnectedOrFirstVehicleId());
            }
            else {
                logger.debug("{}: SOC retrieval is NOT required: {}", applianceId, this.socValues);
            }
        }
        return socChanged;
    }

    private int calculateCurrentSoc() {
        ElectricVehicle vehicle = getConnectedVehicle();
        if (vehicle != null) {
            int energyMeteredSinceLastSocScriptExecution = getEnergyMeteredSinceLastSocScriptExecution();
            int socRetrievedOrInitial = this.socValues.retrieved != null ? this.socValues.retrieved : getSocInitial();
            int soc = Long.valueOf(Math.round(
                    socRetrievedOrInitial + energyMeteredSinceLastSocScriptExecution / (vehicle.getBatteryCapacity() *  (1 + chargeLoss/100)) * 100
            )).intValue();
            int socCurrent = Math.min(soc, 100);
            logger.debug("{}: SOC calculation: socCurrent={} socRetrievedOrInitial={} batteryCapacity={}Wh energyMeteredSinceLastSocScriptExecution={}Wh chargeLoss={}",
                    applianceId, percentageFormat.format(socCurrent), percentageFormat.format(socRetrievedOrInitial),
                    vehicle.getBatteryCapacity(),  energyMeteredSinceLastSocScriptExecution, percentageFormat.format(chargeLoss));
            return socCurrent;
        }
        return 0;
    }

    public void triggerSocScriptExecution() {
       this.evIdWithSocScriptExecutor.values().forEach(executor -> executor.triggerExecution(this));
    }

    public void triggerSocScriptExecution(Integer evId) {
        var executor = evIdWithSocScriptExecutor.get(evId);
        if(executor != null) {
            executor.triggerExecution(this);
        }
    }

    @Override
    public void socRetrieved(int evId, SocScriptExecutionResult result) {
        // TODO use location/plugin time to identify ev if multiple evs may be connected at the same time
        // https://stackoverflow.com/questions/3694380/calculating-distance-between-two-points-using-latitude-longitude

        if(this.connectedVehicleId == null && result.pluggedIn) {
            this.connectedVehicleId = evId;
        }
        if(result.pluggedIn == null || result.pluggedIn) {
            Integer socLastRetrieved = socValues.retrieved != null ? socValues.retrieved : socValues.initial;
            if(socValues.initial == null) {
                socValues.initial = result.soc.intValue();
                socValues.current = result.soc.intValue();
                socValues.batteryCapacity = getVehicle(evId).getBatteryCapacity();
            }
            socValues.retrieved = result.soc.intValue();
            if(socLastRetrieved != null) {
                Double chargeLossCalculated = calculateChargeLoss(getEnergyMeteredSinceLastSocScriptExecution());
                if(chargeLossCalculated != null) {
                    chargeLoss = chargeLossCalculated > 0 ? chargeLossCalculated : 0.0 ;
                }
            }
            socValues.current = result.soc.intValue();
            socRetrievalEnergyMeterValue = meterMessage != null ? meterMessage.energy : 0.0;
            if(this.chargingAlmostCompleted) {
                socRetrievalForChargingAlmostCompleted = true;
            }
        }
    }

    private int getEnergyMeteredSinceLastSocScriptExecution() {
        int energyMeteredSinceLastSocScriptExecution = 0; // in Wh
        Double energyMetered = meterMessage != null ? meterMessage.energy : 0.0;
        energyMeteredSinceLastSocScriptExecution = Double.valueOf((energyMetered - socRetrievalEnergyMeterValue) * 1000.0f).intValue();
        logger.trace("{}: Calculate energyMeteredSinceLastSocScriptExecution={} meterMessage={} energyMetered={} socRetrievalEnergyMeterValue={}",
                applianceId, energyMeteredSinceLastSocScriptExecution, meterMessage != null, energyMetered, socRetrievalEnergyMeterValue);
        return energyMeteredSinceLastSocScriptExecution;
    }

    public double getChargeLoss() {
        return chargeLoss;
    }

    public Double calculateChargeLoss(int energyMeteredSinceLastSocScriptExecution) {
        var evId = getConnectedOrFirstVehicleId();
        var batteryCapacity = evId != null ? this.vehicles.get(evId).getBatteryCapacity() : null;
        if (batteryCapacity != null && energyMeteredSinceLastSocScriptExecution > 0) {
            double energyReceivedByEv = (this.socValues.current - this.socValues.retrieved)/100.0 * batteryCapacity;
            double chargeLoss = energyMeteredSinceLastSocScriptExecution * 100.0 / energyReceivedByEv - 100.0;
            logger.debug("{}: charge loss calculation: chargeLoss={} socCurrent={} socLastRetrieval={} batteryCapacity={}Wh energyMeteredSinceLastSocScriptExecution={}Wh energyReceivedByEv={}Wh",
                    applianceId, percentageFormat.format(chargeLoss), percentageFormat.format(socValues.current), percentageFormat.format(socValues.retrieved),
                    batteryCapacity, energyMeteredSinceLastSocScriptExecution, (int) energyReceivedByEv);
            return chargeLoss;
        }
        return null;
    }
}
