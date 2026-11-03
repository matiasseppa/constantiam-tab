package dev.joinyears;

import dev.joinyears.commands.JoinYearsCommand;
import dev.joinyears.modules.JoinYears;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class JoinYearsAddon extends MeteorAddon {
    public static Category CATEGORY = new Category("Claude's Meteor Addons");

    @Override
    public void onInitialize() {
        Modules.get().add(new JoinYears());
        Commands.add(new JoinYearsCommand());
    }

    @Override
    public void onRegisterCategories() {
        // Shared with the other bundled addons; Meteor groups modules by the category object, so all of them must use the same one
        for (Category category : Modules.loopCategories()) {
            if (category.equals(CATEGORY)) {
                CATEGORY = category;
                return;
            }
        }
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.joinyears";
    }
}
