import os
import streamlit as st
import librosa
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
import pickle

# Аннотаци болон загвар хадгалах файлууд
DATASET_FILE = "annotations.csv"
MODEL_FILE = "audio_model.pkl"

st.set_page_config(page_title="Дуу авиа танигч апп", layout="wide")
st.title("🎵 Дуу авиа аннотаци хийх ба Таних Аппликейшн")

# 1. MFCC Онцлог шинж ялгах функц
def extract_features(file_path):
    try:
        # Librosa-гаар дууны файлыг унших (дэмжих формат: wav, mp3, mp4 г.м)
        audio, sample_rate = librosa.load(file_path, res_type='kaiser_fast')
        # MFCC (Mel-Frequency Cepstral Coefficients) ялгах
        mfccs = librosa.feature.mfcc(y=audio, sr=sample_rate, n_mfcc=40)
        mfccs_scaled = np.mean(mfccs.T, axis=0)
        return mfccs_scaled
    except Exception as e:
        st.error(f"Файл уншихад алдаа гарлаа: {e}")
        return None

# Файл түр хадгалах туслах функц
def save_uploaded_file(uploaded_file):
    temp_dir = "temp_audio"
    os.makedirs(temp_dir, exist_ok=True)
    file_path = os.path.join(temp_dir, uploaded_file.name)
    with open(file_path, "wb") as f:
        f.write(uploaded_file.getbuffer())
    return file_path

# Навигаци цэс
menu = ["1. Аннотаци хийх (Dataset үүсгэх)", "2. Модель сургах", "3. Тестлэх (Дуу таних)"]
choice = st.sidebar.selectbox("Цэс сонгох", menu)

# ==========================================
# ХЭСЭГ 1: АННОТАЦИ ХИЙХ
# ==========================================
if choice == "1. Аннотаци хийх (Dataset үүсгэх)":
    st.header("1. Файл оруулж аннотаци (шошго) хийх")
    st.write("Энд mp3, mp4, wav файл оруулж, ямар төрлийн дуу болохыг шошгож хадгална уу.")

    uploaded_file = st.file_uploader("Дууны файл сонгох", type=["wav", "mp3", "mp4"])
    label_input = st.text_input("Дууны төрөл/Шошго (Жишээ нь: Нохой, Машин, Төгөлдөр хуур, Яриа гэх мэт)")

    if st.button("Шошгоор хадгалах"):
        if uploaded_file is not None and label_input.strip() != "":
            file_path = save_uploaded_file(uploaded_file)
            st.audio(uploaded_file)
            
            # Feature extraction
            st.info("Дууны онцлог шинжийг аналитик хийж байна...")
            features = extract_features(file_path)

            if features is not None:
                # Өгөгдлийг DataFrame болгож CSV-д хадгалах
                data = list(features) + [label_input.strip()]
                columns = [f"mfcc_{i}" for i in range(len(features))] + ["label"]
                
                df_new = pd.DataFrame([data], columns=columns)

                if os.path.exists(DATASET_FILE):
                    df_new.to_csv(DATASET_FILE, mode='a', header=False, index=False)
                else:
                    df_new.to_csv(DATASET_FILE, index=False)

                st.success(f"Амжилттай хадгалагдлаа! Шошго: **{label_input}**")
                
                # Түр файлыг устгах
                os.remove(file_path)
        else:
            st.warning("Файл болон шошгыг бүрэн оруулна уу.")

    # Хадгалагдсан өгөгдлийг харуулах
    if os.path.exists(DATASET_FILE):
        st.subheader("Одоогоор хадгалагдсан өгөгдлийн сан (Dataset):")
        df = pd.read_csv(DATASET_FILE)
        st.write(f"Нийт цугларсан өгөгдөл: **{len(df)}**")
        st.dataframe(df.tail(10)) # Сүүлийн 10 мөр харуулах

# ==========================================
# ХЭСЭГ 2: МОДЕЛЬ СУРГАХ
# ==========================================
elif choice == "2. Модель сургах":
    st.header("2. Цуглуулсан өгөгдлөөр загвар сургах")

    if not os.path.exists(DATASET_FILE):
        st.warning("Одоогоор аннотаци хийсэн өгөгдөл байхгүй байна. Эхлээд 1-р цэсээр орж өгөгдөл цуглуулна уу.")
    else:
        df = pd.read_csv(DATASET_FILE)
        st.write(f"Нийт сургахад бэлэн өгөгдлийн тоо: **{len(df)}**")
        
        # Ангиллуудыг харуулах
        class_counts = df['label'].value_counts()
        st.bar_chart(class_counts)

        if st.button("Модель сургаж эхлэх"):
            if len(df['label'].unique()) < 2:
                st.error("Загвар сургахад доад тал нь **2 өөр төрлийн** шошготой өгөгдөл хэрэгтэй!")
            else:
                st.info("Сургаж байна...")
                X = df.drop(columns=['label'])
                y = df['label']

                # Random Forest загвар сургах
                model = RandomForestClassifier(n_estimators=100, random_state=42)
                model.fit(X, y)

                # Загварыг хадгалах
                with open(MODEL_FILE, "wb") as f:
                    pickle.dump(model, f)

                st.success("Амжилттай сургаж хадгаллаа! Одоо 3-р цэс рүү очиж тестлээрэй.")

# ==========================================
# ХЭСЭГ 3: ТЕСТЛЭХ (ДУУ ТАНИХ)
# ==========================================
elif choice == "3. Тестлэх (Дуу таних)":
    st.header("3. Шинэ дууны файл оруулж таних")

    if not os.path.exists(MODEL_FILE):
        st.error("Сургасан загвар олдсонгүй! Эхлээд 2-р цэс дээр очиж загвараа сургана уу.")
    else:
        uploaded_file = st.file_uploader("Шинэ дууны файл оруулах", type=["wav", "mp3", "mp4"])

        if uploaded_file is not None:
            st.audio(uploaded_file)
            
            if st.button("Ямар дуу болохыг таах"):
                file_path = save_uploaded_file(uploaded_file)
                st.info("Гүйцэтгэж байна...")

                # MFCC ялгах
                features = extract_features(file_path)

                if features is not None:
                    # Загвараа ачааллах
                    with open(MODEL_FILE, "rb") as f:
                        model = pickle.load(f)

                    # Таамаглал хийх
                    prediction = model.predict([features])[0]
                    probs = model.predict_proba([features])[0]
                    classes = model.classes_

                    st.markdown(f"### 🎉 Таамагласан үр дүн: **{prediction}**")

                    # Магадлалуудыг харуулах
                    st.subheader("Магадлалын хуваарилалт:")
                    prob_df = pd.DataFrame({'Төрөл': classes, 'Магадлал': probs})
                    prob_df['Магадлал'] = prob_df['Магадлал'].apply(lambda x: f"{x*100:.2f}%")
                    st.table(prob_df)

                # Түр файлыг устгах
                os.remove(file_path)
