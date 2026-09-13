package dev.mikoto2000.rei.computeruse;
@FunctionalInterface public interface ComputerVisionModel { ComputerAction decide(ComputerObservation observation) throws Exception; }
