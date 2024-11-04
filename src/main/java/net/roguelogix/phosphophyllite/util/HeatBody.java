package net.roguelogix.phosphophyllite.util;

public class HeatBody {
    
    private boolean isInfinite = false;
    private double rfPerKelvin = 0;
    private double temperature = 0;
    
    public void setInfinite(boolean infinite) {
        isInfinite = infinite;
    }
    
    public boolean isInfinite() {
        return isInfinite;
    }
    
    public void setRfPerKelvin(double rfPerKelvin) {
        this.rfPerKelvin = rfPerKelvin;
    }
    
    public double rfPerKelvin(){
        return rfPerKelvin;
    }
    
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
    
    public double temperature(){
        return temperature;
    }
    
    /**
     * bi directional heat transfer between two heat bodies
     *
     * @param other other heat body
     * @param rfkt  RF per kelvin tick, basically watts per metre squared kelvin
     * @return RF trasnfered from this to other
     */
    public double transferWith(HeatBody other, double rfkt) {
        // cant transfer between two infinite bodies
        if (isInfinite && other.isInfinite()) {
            return 0;
        }
        if (!isInfinite && other.isInfinite()) {
            // i dont want to implement it for both ways, so im just gonna assume that if one of them is infinite, its this one
            return -other.transferWith(this, rfkt);
        }
        
        double rfTransferred;
        
        if (!isInfinite) {
            
            double targetTemperature = ((this.rfPerKelvin * (this.temperature - other.temperature())) / (this.rfPerKelvin + other.rfPerKelvin()));
            targetTemperature += other.temperature;
            
            double denominator = rfkt * (this.rfPerKelvin + other.rfPerKelvin());
            denominator /= this.rfPerKelvin * other.rfPerKelvin();
            denominator = Math.exp(-denominator);
            
            double thisNewTemp = this.temperature - targetTemperature;
            thisNewTemp *= denominator; // its flipped in the exp
            thisNewTemp += targetTemperature;
    
            double otherNewTemp = other.temperature() - targetTemperature;
            otherNewTemp *= denominator; // its flipped in the exp
            otherNewTemp += targetTemperature;
            
            rfTransferred = (otherNewTemp - other.temperature()) * other.rfPerKelvin();
            
            this.temperature = thisNewTemp;
            other.setTemperature(otherNewTemp);
            
        } else {
            
            double newTemp = other.temperature() - temperature;
            newTemp *= Math.exp(-rfkt / other.rfPerKelvin());
            newTemp += temperature;
    
            rfTransferred = (newTemp - other.temperature()) * other.rfPerKelvin();
    
            other.setTemperature(newTemp);
        }
        
        return rfTransferred;
    }
    
    public double additionalRFForTemperature(double targetTemperature) {
        double currentRF = rfFromTemperature(temperature);
        double targetRF = rfFromTemperature(targetTemperature);
        return targetRF - currentRF;
    }
    
    public double temperatureWithAdditionalRF(double rf) {
        return temperature + (isInfinite ? 0 : rf / (rfPerKelvin));
    }
    
    public double absorbRF(double rf) {
        this.temperature = temperatureWithAdditionalRF(rf);
        return rf;
    }
    
    public double rfFromTemperature(double temperature) {
        return temperature * rfPerKelvin;
    }
    
    public double rf() {
        return rfFromTemperature(temperature);
    }
    
    public double tempFromRF(double rf) {
        return rf / (rfPerKelvin);
    }
}
