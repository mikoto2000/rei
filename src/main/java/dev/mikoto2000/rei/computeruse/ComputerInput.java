package dev.mikoto2000.rei.computeruse;
@FunctionalInterface public interface ComputerInput { void execute(ComputerAction action, CapturedScreen screen) throws Exception; }
