package org.tensorflow.lite.examples.detection;

import android.app.DialogFragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class EditSettingsDialogFragment extends DialogFragment implements View.OnClickListener {

    private static final String TAG = "TF-Detection";
    private String mEditIP;
    private String mEditPort;

    public interface EditSettingsDialogListener {
        void onFinishEditDialog(String ip, String port);
    }

    public EditSettingsDialogFragment() {
    }

    public static EditSettingsDialogFragment newInstance(String ip, String port) {
        EditSettingsDialogFragment frag = new EditSettingsDialogFragment();
        Bundle args = new Bundle();
        args.putString("ip", ip);
        args.putString("port", port);
        frag.setArguments(args);
        return frag;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog, container);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Get field from view
        TextView ipText = view.findViewById(R.id.editText_IP);
        TextView portText = view.findViewById(R.id.editText_Port);
        String ip = getArguments().getString("ip");
        String port = getArguments().getString("port");
        ipText.setText(ip);
        portText.setText(port);
        mEditIP = ipText.getText().toString();
        mEditPort = portText.getText().toString();
        Button save = (Button)getView().findViewById(R.id.save_settings);

        save.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        int viewId = view.getId();
        if(viewId == R.id.save_settings) {
            // Get field from view
            TextView ipText = getView().findViewById(R.id.editText_IP);
            TextView portText = getView().findViewById(R.id.editText_Port);
            mEditIP = ipText.getText().toString();
            mEditPort = portText.getText().toString();

//            EditSettingsDialogListener listener = (EditSettingsDialogListener) getTargetFragment();
            EditSettingsDialogListener listener = (EditSettingsDialogListener) getActivity();
            if (listener != null) {
                listener.onFinishEditDialog(mEditIP,mEditPort);
                // Close the dialog and return back to the parent activity
            }

            dismiss();
//            ((MainActivity) getActivity()).getSupportFragmentManager().beginTransaction().replace(R.id.content_frame, new DetectionFragment()).addToBackStack(null).commit();
        }
    }
}
