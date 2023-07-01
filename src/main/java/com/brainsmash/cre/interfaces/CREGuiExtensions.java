package com.brainsmash.cre.interfaces;

import com.brainsmash.cre.gui.CREGuiComponents;

public interface CREGuiExtensions {
    void fabric_nextPage();

    void fabric_previousPage();

    int fabric_currentPage();

    boolean fabric_isButtonVisible(CREGuiComponents.Type type);

    boolean fabric_isButtonEnabled(CREGuiComponents.Type type);
}
